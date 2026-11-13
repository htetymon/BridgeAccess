package com.eduaccess.bridgeaccess.service.image;

import java.io.IOException;

/** Test images for other packages' tests. */
public final class ImageOptimizerTestSupport {

	private ImageOptimizerTestSupport() {
	}

	/** A photo-like PNG that does not compress well, as a contributor upload might be. */
	public static byte[] photoPng(int width, int height) throws IOException {
		return ImageOptimizerTest.encode(ImageOptimizerTest.photo(width, height), "png");
	}

}
