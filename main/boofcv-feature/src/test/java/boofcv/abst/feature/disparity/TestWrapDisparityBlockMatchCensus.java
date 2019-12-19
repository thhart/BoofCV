/*
 * Copyright (c) 2011-2019, Peter Abeles. All Rights Reserved.
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

package boofcv.abst.feature.disparity;

import boofcv.factory.feature.disparity.ConfigDisparityBM;
import boofcv.factory.feature.disparity.DisparityError;
import boofcv.factory.feature.disparity.FactoryStereoDisparity;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageType;
import org.junit.jupiter.api.Nested;

/**
 * @author Peter Abeles
 */
public class TestWrapDisparityBlockMatchCensus {

	@Nested
	class CENSUS_U8 extends GenericStereoDisparityChecks<GrayU8,GrayU8> {

		public CENSUS_U8() {
			super(ImageType.SB_U8, ImageType.SB_U8);
		}

		@Override
		public StereoDisparity<GrayU8, GrayU8> createAlg(int disparityMin, int disparityRange) {
			ConfigDisparityBM config = new ConfigDisparityBM();
			config.errorType = DisparityError.CENSUS;
			config.subpixel = false;
			config.disparityMin = disparityMin;
			config.disparityRange = disparityRange;
			return FactoryStereoDisparity.blockMatch(config,inputType.getImageClass(),disparityType.getImageClass());
		}
	}

	@Nested
	class CENSUS_F32 extends GenericStereoDisparityChecks<GrayU8, GrayF32> {

		public CENSUS_F32() {
			super(ImageType.SB_U8, ImageType.SB_F32);
		}

		@Override
		public StereoDisparity<GrayU8, GrayF32> createAlg(int disparityMin, int disparityRange) {
			ConfigDisparityBM config = new ConfigDisparityBM();
			config.errorType = DisparityError.CENSUS;
			config.subpixel = true;
			config.disparityMin = disparityMin;
			config.disparityRange = disparityRange;
			return FactoryStereoDisparity.blockMatch(config,inputType.getImageClass(),disparityType.getImageClass());
		}
	}
}
