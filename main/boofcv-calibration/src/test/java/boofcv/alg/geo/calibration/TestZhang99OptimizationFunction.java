/*
 * Copyright (c) 2011-2018, Peter Abeles. All Rights Reserved.
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

package boofcv.alg.geo.calibration;

import boofcv.alg.geo.calibration.pinhole.CalibParamPinholeRadial;
import boofcv.alg.geo.calibration.pinhole.TestPinholeCalibrationZhang99;
import boofcv.struct.calib.CameraPinholeRadial;
import georegression.geometry.ConvertRotation3D_F64;
import georegression.struct.point.Point2D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import georegression.transform.se.SePointOps_F64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Peter Abeles
 */
public class TestZhang99OptimizationFunction {

	Random rand = new Random(234);

	/**
	 * Give it perfect observations and see if the residuals are all zero
	 */
	@Test
	public void computeResidualsPerfect() {
		Zhang99AllParam param = GenericCalibrationGrid.createStandardParam(
				TestPinholeCalibrationZhang99.createStandard(false,  true,2,rand), 3, rand);

		double array[] = new double[ param.numParameters() ];
		param.convertToParam(array);
		
		List<Point2D_F64> gridPts = GenericCalibrationGrid.standardLayout();

		List<CalibrationObservation> observations = new ArrayList<>();

		for( int i = 0; i < param.views.length; i++ ) {
			observations.add( estimate(param,param.views[i],gridPts));
		}

		Zhang99OptimizationFunction alg =
				new Zhang99OptimizationFunction( new Zhang99AllParam(new CalibParamPinholeRadial(false,2,true)
						,3),gridPts,observations );

		double residuals[] = new double[ alg.getNumOfOutputsM()];
		for( int i = 0; i < residuals.length; i++ )
			residuals[i] = 1;
		
		alg.process(array,residuals);
		
		for( double r : residuals ) {
			assertEquals(0,r,1e-8);
		}
	}
	
	protected static CalibrationObservation estimate( Zhang99AllParam param ,
													  Zhang99AllParam.View v ,
													  List<Point2D_F64> grid ) {

		CalibrationObservation ret = new CalibrationObservation();
		
		Se3_F64 se = new Se3_F64();
		Point3D_F64 cameraPt = new Point3D_F64();
		Point2D_F64 calibratedPt = new Point2D_F64();

		ConvertRotation3D_F64.rodriguesToMatrix(v.rotation, se.getR());
		se.T = v.T;

		CameraPinholeRadial intrinsic = param.getIntrinsic().getCameraModel();

		for( int i = 0; i < grid.size(); i++ ) {
			Point2D_F64 gridPt = grid.get(i);
			
			// Put the point in the camera's reference frame
			SePointOps_F64.transform(se, new Point3D_F64(gridPt.x,gridPt.y,0), cameraPt);

			// calibrated pixel coordinates
			calibratedPt.x = cameraPt.x/ cameraPt.z;
			calibratedPt.y = cameraPt.y/ cameraPt.z;

			// apply radial distortion
			CalibrationPlanarGridZhang99.applyDistortion(calibratedPt, intrinsic.radial,intrinsic.t1,intrinsic.t2);

			// convert to pixel coordinates
			double x = intrinsic.fx*calibratedPt.x + intrinsic.skew*calibratedPt.y + intrinsic.cx;
			double y = intrinsic.fy*calibratedPt.y + intrinsic.cy;
			
			ret.add( new Point2D_F64(x,y), i);
		}

		return ret;
	}

	/**
	 * Have there only be partial observations of the fiducial
	 */
	@Test
	public void computeResidualsPerfect_partial() {
		Zhang99AllParam param = GenericCalibrationGrid.createStandardParam(
				TestPinholeCalibrationZhang99.createStandard(false, true,2,rand), 3, rand);

		double array[] = new double[ param.numParameters() ];
		param.convertToParam(array);

		List<Point2D_F64> gridPts = GenericCalibrationGrid.standardLayout();

		List<CalibrationObservation> observations = new ArrayList<>();

		for( int i = 0; i < param.views.length; i++ ) {
			CalibrationObservation set = estimate(param, param.views[i], gridPts);
			observations.add( set );

			for (int j = 0; j < 5; j++) {
				set.points.remove(i * 2 + j);
			}
		}

		int expectedM = 0;
		for (int i = 0; i < observations.size(); i++) {
			expectedM += observations.get(i).size()*2;
		}

		Zhang99OptimizationFunction alg =
				new Zhang99OptimizationFunction( new Zhang99AllParam(
						new CalibParamPinholeRadial(false,2,true),3),gridPts,observations );

		assertEquals(expectedM,alg.getNumOfOutputsM());
		double residuals[] = new double[ alg.getNumOfOutputsM()];
		for( int i = 0; i < residuals.length; i++ )
			residuals[i] = 1;

		alg.process(array, residuals);

		for( double r : residuals ) {
			assertEquals(0,r,1e-8);
		}
	}

}
