/*
 * Copyright (c) 2011-2012, Peter Abeles. All Rights Reserved.
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

package boofcv.alg.feature.disparity;

import boofcv.core.image.GeneralizedImageOps;
import boofcv.misc.PerformerBase;
import boofcv.misc.ProfileOperation;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageUInt8;

import java.util.Random;

/**
 * @author Peter Abeles
 */
public class BenchmarkDisparityAlgs {
	static final long TEST_TIME = 1000;
	static final Random rand = new Random(234234);

	static final int width=640;
	static final int height=480;
	static final int max=20;
	static final int radiusX=2;
	static final int radiusY=2;



	static final ImageUInt8 left = new ImageUInt8(width,height);
	static final ImageUInt8 right = new ImageUInt8(width,height);

	static final ImageUInt8 outU8 = new ImageUInt8(width,height);
	static final ImageFloat32 out_F32 = new ImageFloat32(width,height);

	public static class Naive extends PerformerBase {

		StereoDisparityWtoNaive<ImageUInt8> alg =
				new StereoDisparityWtoNaive<ImageUInt8> (max,radiusX,radiusY);

		@Override
		public void process() {
			alg.process(left,right,out_F32);
		}
	}

	public static class EfficientSad extends PerformerBase {

//		DisparitySelect_S32<ImageUInt8> compDisp =
//				new SelectRectBasicWta_S32_U8();
		DisparitySelect_S32<ImageUInt8> compDisp =
				new SelectRectStandard_S32_U8(250,2,0.1);
		DisparityScoreSadRect_U8<ImageUInt8> alg =
				new DisparityScoreSadRect_U8<ImageUInt8>(max,radiusX,radiusY,compDisp);

		@Override
		public void process() {
			alg.process(left,right, outU8);
		}
	}

	public static class EfficientSubpixelSad extends PerformerBase {

		DisparitySelect_S32<ImageFloat32> compDisp =
				new SelectRectSubpixel_S32_F32(250,2,0.1);
		DisparityScoreSadRect_U8<ImageFloat32> alg =
				new DisparityScoreSadRect_U8<ImageFloat32>(max,radiusX,radiusY,compDisp);

		@Override
		public void process() {
			alg.process(left,right, out_F32);
		}
	}

	public static void main( String argsp[ ] ) {
		System.out.println("=========  Image Size "+ width +" "+height+"  disparity "+max);
		System.out.println();

		GeneralizedImageOps.randomize(left,rand,0,30);
		GeneralizedImageOps.randomize(right,rand,0,30);

		// the "fastest" seems to always be the first one tested
		ProfileOperation.printOpsPerSec(new EfficientSad(),TEST_TIME);
		ProfileOperation.printOpsPerSec(new EfficientSubpixelSad(),TEST_TIME);
		ProfileOperation.printOpsPerSec(new Naive(), TEST_TIME);

	}
}
