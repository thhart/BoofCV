/*
 * Copyright (c) 2011-2020, Peter Abeles. All Rights Reserved.
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

package boofcv.alg.feature.disparity.block.select;

import boofcv.alg.feature.disparity.block.SelectDisparityWithChecksWta;
import boofcv.alg.feature.disparity.block.SelectSparseStandardWta;
import boofcv.alg.feature.disparity.block.score.DisparitySparseRectifiedScoreBM;

/**
 * <p>
 * Implementation of {@link SelectSparseStandardWta} for score arrays of type S32.
 * </p>
 *
 * <p>
 * DO NOT MODIFY. Generated by GenerateSelectSparseStandardWta.
 * </p>
 *
 * @author Peter Abeles
 */
public class SelectSparseErrorWithChecksWta_S32 extends SelectSparseStandardWta<int[]> {

	// texture threshold, use an integer value for speed.
	protected int textureThreshold;
	protected static final int discretizer = SelectDisparityWithChecksWta.DISCRETIZER;

	public SelectSparseErrorWithChecksWta_S32(int maxError, double texture, int tolRightToLeft) {
		super(maxError,texture,tolRightToLeft);
	}

	@Override
	protected void setTexture( double texture ) {
		this.textureThreshold = (int)(discretizer*texture);
	}

	@Override
	public boolean select(DisparitySparseRectifiedScoreBM<int[],?> scorer, int x, int y) {
		// First compute the error in the normal left to right direction
		if( !scorer.processLeftToRight(x,y) )
			return false;
		int[] scores = scorer.getScoreLtoR();
		int disparityRange = scorer.getLocalRangeLtoR();

		// Select the disparity with the best error
		int bestDisparity = 0;
		int scoreBest = scores[0];

		for(int i = 1; i < disparityRange; i++ ) {
			if( scores[i] < scoreBest ) {
				scoreBest = scores[i];
				bestDisparity = i;
			}
		}

		// See if the best match is within tolerance
		if( scoreBest > maxError ) {
			return false;
		}
		// test to see if the region lacks sufficient texture if:
		// 1) not already eliminated 2) sufficient disparities to check, 3) it's activated
		if( textureThreshold > 0 && disparityRange >= 3 ) {
			// find the second best disparity value and exclude its neighbors
			int secondBest = Integer.MAX_VALUE;
			for( int i = 0; i < bestDisparity-1; i++ ) {
				if( scores[i] < secondBest )
					secondBest = scores[i];
			}
			for(int i = bestDisparity+2; i < disparityRange; i++ ) {
				if( scores[i] < secondBest )
					secondBest = scores[i];
			}

			// similar scores indicate lack of texture
			// C = (C2-C1)/C1
			if( discretizer*(secondBest-scoreBest) <= textureThreshold*scoreBest )
				return false;
		}

		// if requested perform right to left validation. Ideally the two disparities will be identical
		if( tolRightToLeft >= 0 ) {
			if( !scorer.processRightToLeft(x-bestDisparity-scorer.getDisparityMin(),y) )
				return false;
			final int[] scoresRtoL = scorer.getScoreRtoL();
			final int localRangeRtoL = scorer.getLocalRangeRtoL();
			int bestDisparityRtoL = 0;
			int scoreBestRtoL = scoresRtoL[0];

			for(int i = 1; i < localRangeRtoL; i++ ) {
				if( scoresRtoL[i] < scoreBestRtoL ) {
					scoreBestRtoL = scoresRtoL[i];
					bestDisparityRtoL = i;
				}
			}
			if( Math.abs(bestDisparityRtoL-bestDisparity) > tolRightToLeft )
				return false;
		}

		this.disparity = bestDisparity;

		return true;
	}

}
