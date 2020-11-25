/*
 * Copyright (c) 2020, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.mvs;

import boofcv.abst.disparity.StereoDisparity;
import boofcv.abst.geo.bundle.BundleAdjustmentCamera;
import boofcv.abst.geo.bundle.SceneStructureMetric;
import boofcv.alg.distort.brown.LensDistortionBrown;
import boofcv.alg.geo.bundle.BundleAdjustmentOps;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.misc.BoofMiscOps;
import boofcv.struct.calib.CameraPinholeBrown;
import boofcv.struct.distort.Point2Transform2_F64;
import boofcv.struct.distort.PointToPixelTransform_F64;
import boofcv.struct.image.*;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import lombok.Getter;
import lombok.Setter;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_I32;
import org.ddogleg.struct.VerbosePrint;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.*;

import static java.util.Objects.requireNonNull;

/**
 * Creates a dense point cloud from multiple stereo pairs. When possible, a single disparity image is computed using
 * multiple stereo pairs with a single "center" image that is common to all pairs. This allows noise to be reduced
 * using the redundant information. A combined 3D point cloud is then found using multiple disparity images while
 * removing redundant points.
 *
 * <ol>
 *     <li>Compute the score for every view if it was a "center" view among multiple stereo pairs</li>
 *     <li>Sort views based on scores with the best first</li>
 *     <li>Greedily select views to act as a center view and compute a combined disparity image from its neighbors</li>
 *     <lI>Add a disparity image to the combined 3D point cloud while pruning pixels which are too similar</lI>
 * </ol>
 *
 * If you just want the point cloud then calling {@link #getCloud()} is what you need. If information on each view's
 * contribution to the point cloud is needed then you need to call {@link #getDisparityCloud()} and access
 * the view specific results.
 *
 * NOTE: Before this can be used you must call {@link #setStereoDisparity}.
 *
 * @see ScoreRectifiedViewCoveragePixels
 * @see MultiViewToFusedDisparity
 * @see CreateCloudFromDisparityImages
 *
 * * @author Peter Abeles
 */
public class MultiViewStereoFromKnownSceneStructure<T extends ImageGray<T>> implements VerbosePrint {
	/**
	 * Specifies the minimum quality of the 3D information between two views for it to be used as a stereo pair.
	 *
	 * @see ScoreRectifiedViewCoveragePixels
	 */
	public @Getter @Setter double minimumQuality3D = 0.25;

	/** Used to access temporary results before they are discarded */
	protected @Getter @Setter @Nullable Listener<T> listener;

	/** Which views acted as "centers" and contributed to the point cloud */
	protected final @Getter List<ViewInfo> listCenters = new ArrayList<>();

	// Used to retrieve images from a data base. There could be easily too images to load all at once
	final LookUpImages imageLookUp;
	// where all the images needed for a single set of stereo pairs are stored
	final FastQueue<T> imageStorage;

	/** Computes the score for a given view acting as the "center" among its neighbors */
	final @Getter ScoreRectifiedViewCoveragePixels scoreCoverage = new ScoreRectifiedViewCoveragePixels();

	/** Computes rectification given intrinsic and extrinsic parameters */
	final @Getter
	BundleToRectificationStereoParameters computeRectification = new BundleToRectificationStereoParameters();
	/**
	 * Given one "center" view and several other views which form a stereo pair, compute a single
	 * combined disparity image
	 */
	final @Getter MultiViewToFusedDisparity<T> computeFused = new MultiViewToFusedDisparity<>();
	/** Combine multiple disparity images into a single point cloud while avoiding redundant points */
	final @Getter CreateCloudFromDisparityImages disparityCloud = new CreateCloudFromDisparityImages();

	//-------------------------------------------------------------------------
	//------- Internal workspace variables

	// Quick look up of view info by ID
	final Map<String, ViewInfo> mapScores = new HashMap<>();
	final FastQueue<ViewInfo> arrayScores = new FastQueue<>(ViewInfo::new, ViewInfo::reset);

	// Used to look up ViewInfo from SBA index
	TIntObjectMap<String> indexSbaToViewID = new TIntObjectHashMap<>();

	// storage for images used to compute the disparity for this set of stereo views
	TIntObjectMap<T> indexSbaToImage = new TIntObjectHashMap<>();
	// Which SBA view indexes are paired to the target
	GrowQueue_I32 imagePairIndexesSba = new GrowQueue_I32();

	// Used when a stereo mask is required but none is available
	GrayU8 dummyMask = new GrayU8(1, 1);

	CameraPinholeBrown brown = new CameraPinholeBrown();

	// translation from world to the center view
	Se3_F64 world_to_view1 = new Se3_F64();
	// translation from world to the second view in the stereo pair
	Se3_F64 world_to_view2 = new Se3_F64();
	// center to pairs view
	Se3_F64 view1_to_view2 = new Se3_F64();
	// Workspace
	Se3_F64 tmp = new Se3_F64();

	@Nullable PrintStream verbose = null;

	public MultiViewStereoFromKnownSceneStructure( LookUpImages imageLookUp, ImageType<T> imageType ) {
		this.imageLookUp = imageLookUp;
		imageStorage = new FastQueue<>(() -> imageType.createImage(1, 1));
	}

	/**
	 * Computes a point cloud given the known scene and a set of stereo pairs.
	 *
	 * @param scene (Input) Specifies the scene parameters for each view. Extrinsic and intrinsic.
	 * @param pairs (Input) Which views are to be used and their relationship to each other
	 */
	public void process( SceneStructureMetric scene, StereoPairGraph pairs ) {
		initializeListener();
		// Go through each view and compute score for use as a common / "left" stereo image
		initializeScores(scene, pairs);
		scoreViewsSelectStereoPairs(scene);

		// Initialize data structures
		disparityCloud.reset();
		listCenters.clear();

		// Sort views based on their score for being the center view. best views are first
		Collections.sort(arrayScores.toList(), Comparator.comparingDouble(a -> -a.score));

		// Go through the list of views and use unused views as center views when computing the overall 3D cloud
		for (int index = 0; index < arrayScores.size; index++) {
			ViewInfo center = arrayScores.get(index);
			// if already processed skip over
			if (center.used)
				continue;

			if (verbose != null) verbose.println("Center[" + index + "] View=" + center.relations.id);

			// TODO compute fraction of view area which has already been covered by a previous center
			//      and skip if over a certain value to avoid wasting time. This can happen if a view is very
			//      similar to a "center" view and not used due to lack of 3D information between the two

			// Compute a disparity image for this cluster of stereo pairs
			selectAndLoadConnectedImages(pairs, center.relations);

			// If none of the connected views had enough quality abort
			if (indexSbaToImage.size() < 1) {
				if (verbose != null) verbose.println("  too few connections to use as a center");
				continue;
			}

			// Record that this view was used as a center
			listCenters.add(center);

			// Add image for center view
			addImageToMap(center.relations);
			// Remove the tail since only the pair views can be in this list
			imagePairIndexesSba.removeTail();

			// Compute the fused disparity from all the views, then add points to the point cloud
			computeFusedDisparityAddCloud(scene, center, indexSbaToImage, imagePairIndexesSba);
		}
	}

	/**
	 * Sets up the listener for individual stereo pairs
	 */
	void initializeListener() {
		Objects.requireNonNull(computeFused.getStereoDisparity(), "Must call setStereoDisparity() first");

		if (listener != null) {
			computeFused.setListener(( left, right, rectLeft, rectRight, disparity, mask, parameters, rect ) -> {
				String leftID = indexSbaToViewID.get(left);
				String rightID = indexSbaToViewID.get(right);
				this.listener.handlePairDisparity(leftID, rightID, rectLeft, rectRight, disparity, mask, parameters);
			});
		} else {
			computeFused.setListener(null);
		}
	}

	/**
	 * For each view in the list of stereo pairs create a {@link ViewInfo} for it and set all parameters
	 * but the score
	 */
	void initializeScores( SceneStructureMetric scene, StereoPairGraph stereoPairs ) {
		arrayScores.resize(stereoPairs.vertexes.size());
		arrayScores.reset();
		mapScores.clear();
		for (StereoPairGraph.Vertex node : stereoPairs.vertexes.values()) {
			ViewInfo info = arrayScores.grow();
			info.metric = scene.views.get(node.indexSba);
			imageLookUp.loadShape(node.id, info.dimension);
			info.index = arrayScores.size - 1;
			info.relations = node;

			mapScores.put(info.relations.id, info);
		}
	}

	/**
	 * Compute the score for using each view as the center based on coverage and geometric quality
	 */
	void scoreViewsSelectStereoPairs( SceneStructureMetric scene ) {
		for (int i = 0; i < arrayScores.size; i++) {
			ViewInfo center = arrayScores.get(i);
			List<StereoPairGraph.Edge> pairs = center.relations.pairs;
			BundleAdjustmentCamera candidateCamera = scene.cameras.get(center.metric.camera).model;
			computeRectification.setView1(candidateCamera, center.dimension.width, center.dimension.height);

			scoreCoverage.initialize(center.dimension.width, center.dimension.height,
					computeRectification.view1_dist_to_undist);

			scene.getWorldToView(center.metric, world_to_view1, tmp);

			// Compute statistics for verbose mode
			int totalQualifiedConnections = 0;
			double averageQuality = 0.0;

			for (int pairIdx = 0; pairIdx < pairs.size(); pairIdx++) {
				StereoPairGraph.Edge pair = pairs.get(pairIdx);
				// sanity check, since this can be hard to debug if done wrong
				BoofMiscOps.checkTrue(pair.quality3D >= 0.0 && pair.quality3D <= 1.0);

				// Skip if insufficient geometric information
				if (pair.quality3D < minimumQuality3D)
					continue;

				totalQualifiedConnections++;
				averageQuality += pair.quality3D;

				// Look up information that the "center" under consideration is connected to
				ViewInfo connected = mapScores.get(pair.other(center.relations).id);
				BundleAdjustmentCamera connectedCamera = scene.cameras.get(connected.metric.camera).model;

				// Compute the transform from view-1 to view-2
				scene.getWorldToView(connected.metric, world_to_view2, tmp);
				world_to_view1.invert(tmp).concat(world_to_view2, view1_to_view2);

				// Compute rectification then apply coverage with geometric score
				computeRectification.processView2(connectedCamera,
						connected.dimension.width, connected.dimension.height, view1_to_view2);
				scoreCoverage.addView(connected.dimension.width, connected.dimension.height,
						computeRectification.rect2, (float)pair.quality3D);
			}

			// Look at the sum of all information and see what the score is
			scoreCoverage.process();
			center.score = scoreCoverage.getScore();
			if (verbose != null) {
				averageQuality = totalQualifiedConnections > 0 ? averageQuality/totalQualifiedConnections : -1;
				verbose.println("View[" + center.relations.id + "] center score=" + center.score +
						" aveQuality=" + averageQuality + " " +
						" conn=" + totalQualifiedConnections + "/" + pairs.size());
			}
		}
	}

	/**
	 * Combing stereo information from all images in this cluster, compute a disparity image and add it to the cloud
	 */
	void computeFusedDisparityAddCloud( SceneStructureMetric scene, ViewInfo center,
										TIntObjectMap<T> imageMap, GrowQueue_I32 pairIndexes ) {
		computeFused.initialize(scene, imageMap);
		if (!computeFused.process(center.relations.indexSba, pairIndexes))
			throw new RuntimeException("Disparity failed!");

		// The fused disparity doesn't compute a mask since all invalid pixels are marked as invalid using
		// he disparity value
		GrayF32 disparity = computeFused.fusedDisparity;
		dummyMask.reshape(disparity);
		ImageMiscOps.fill(dummyMask, 0);

		// Pass along results to the listener
		if (listener != null) {
			listener.handleFusedDisparity(center.relations.id, disparity, dummyMask, computeFused.fusedParam);
		}

		// Convert data structures into a format which is understood by disparity to cloud
		BundleAdjustmentCamera camera = scene.cameras.get(center.metric.camera).model;
		BundleAdjustmentOps.convert(camera, disparity.width, disparity.height, brown);
		// The fused disparity is in regular pixels and not rectified
		Point2Transform2_F64 norm_to_pixel = new LensDistortionBrown(brown).distort_F64(false, true);
		Point2Transform2_F64 pixel_to_norm = new LensDistortionBrown(brown).undistort_F64(true, false);
		// world/cloud coordinates into this view
		scene.getWorldToView(center.metric, world_to_view1, tmp);

		// Use the computed disparity to add to the common point cloud while not adding points already in
		// the cloud
		disparityCloud.addDisparity(disparity, dummyMask, world_to_view1, computeFused.fusedParam,
				norm_to_pixel, new PointToPixelTransform_F64(pixel_to_norm));
	}

	/**
	 * Select views connected to the "center" view and if there's enough geometric information between the two
	 * add it to the list of views which is used in this cluster and mark them as used.
	 */
	void selectAndLoadConnectedImages( StereoPairGraph pairs, StereoPairGraph.Vertex center ) {
		imageStorage.reset();
		indexSbaToImage.clear();
		indexSbaToViewID.clear();
		imagePairIndexesSba.reset();

		List<StereoPairGraph.Edge> connections = requireNonNull(pairs.vertexes.get(center.id)).pairs;
		for (int connIdx = 0; connIdx < connections.size(); connIdx++) {
			StereoPairGraph.Edge connected = connections.get(connIdx);

			// Check to see if there's enough 3D information
			if (connected.quality3D < minimumQuality3D)
				continue;

			// Look up the "other" view this is connected to
			StereoPairGraph.Vertex other = connected.other(center);

			if (verbose != null) verbose.println("  connected.id=" + other.id);

			// Add the image associated with "other"
			addImageToMap(other);

			// This view has been consumed so it can't be used as a center view
			mapScores.get(other.id).used = true;
		}
	}

	/** Load the pixel image and add it to the set of images in this cluster */
	void addImageToMap( StereoPairGraph.Vertex view ) {
		T image = imageStorage.grow();
		if (!imageLookUp.loadImage(view.id, image))
			throw new RuntimeException("Failed to look up image: " + view.id);

		indexSbaToImage.put(view.indexSba, image);
		indexSbaToViewID.put(view.indexSba, view.id);
		imagePairIndexesSba.add(view.indexSba);
	}

	/** Returns the computed 3D point cloud. */
	public List<Point3D_F64> getCloud() {
		return disparityCloud.cloud.toList();
	}

	/** Specifies which stereo disparity algorithm to use */
	public void setStereoDisparity( StereoDisparity<T, GrayF32> stereoDisparity ) {
		computeFused.setStereoDisparity(stereoDisparity);
	}

	@Override public void setVerbose( @Nullable PrintStream out, @Nullable Set<String> configuration ) {
		this.verbose = out;
	}

	/** Information on each view that's used to select and compute the disparity images */
	public static class ViewInfo {
		// Reference to the known extrinsic and intrinsic parameters
		SceneStructureMetric.View metric;
		// Describes the relationship this view has with other views
		StereoPairGraph.Vertex relations;
		// The shape the original image
		final ImageDimension dimension = new ImageDimension();
		// Index of this view in the array
		int index;
		// How good of a "center" view this view would be
		double score;
		// Indicates if it has already been used to compute a disparity image or not
		boolean used;

		void reset() {
			metric = null;
			relations = null;
			dimension.setTo(0, 0);
			index = -1;
			score = -1;
			used = false;
		}
	}

	/** Used to capture intermediate results */
	public interface Listener<RectImg> {
		/**
		 * After a regular disparity image has been computed from a pair, this function is called and the results
		 * passed in
		 */
		void handlePairDisparity( String left, String right, RectImg rectLeft, RectImg rectRight,
								  GrayF32 disparity, GrayU8 mask,
								  DisparityParameters parameters );

		/**
		 * After a fused disparity image has been computed, this function is called and the results passed in
		 */
		void handleFusedDisparity( String centerViewName, GrayF32 disparity, GrayU8 mask,
								   DisparityParameters parameters );
	}
}