package com.eduaccess.bridgeaccess.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageTypeTest {

	@Test
	void detectsBySignature() {
		assertThat(ImageType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).contains(ImageType.PNG);
		assertThat(ImageType.detect(bytes(0xFF, 0xD8, 0xFF, 0xDB))).contains(ImageType.JPEG);
		assertThat(ImageType.detect(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P')))
			.contains(ImageType.WEBP);
	}

	@Test
	void rejectsOtherOrTruncatedFiles() {
		assertThat(ImageType.detect(bytes('G', 'I', 'F', '8', '9', 'a'))).isEmpty();
		assertThat(ImageType.detect(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'))).isEmpty();
		assertThat(ImageType.detect(bytes('<', 's', 'v', 'g'))).isEmpty();
		assertThat(ImageType.detect(bytes(0x89, 'P', 'N'))).isEmpty();
		assertThat(ImageType.detect(new byte[0])).isEmpty();
	}

	private static byte[] bytes(int... values) {
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = (byte) values[i];
		}
		return out;
	}

}
