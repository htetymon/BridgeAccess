package com.eduaccess.bridgeaccess.service;

import java.util.Optional;

/**
 * The image formats a contributor may upload (PNG, JPG, WebP). The format is read from the
 * file's leading bytes, never from the declared Content-Type or file name, which the client
 * controls.
 */
public enum ImageType {

	PNG("image/png", "png"), JPEG("image/jpeg", "jpg"), WEBP("image/webp", "webp");

	private final String mimeType;

	private final String extension;

	ImageType(String mimeType, String extension) {
		this.mimeType = mimeType;
		this.extension = extension;
	}

	public String mimeType() {
		return this.mimeType;
	}

	public String extension() {
		return this.extension;
	}

	public static Optional<ImageType> detect(byte[] bytes) {
		if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			return Optional.of(PNG);
		}
		if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
			return Optional.of(JPEG);
		}
		// RIFF container: "RIFF" <4-byte size> "WEBP"
		if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
			return Optional.of(WEBP);
		}
		return Optional.empty();
	}

	private static boolean startsWith(byte[] bytes, int offset, int... signature) {
		if (bytes.length < offset + signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if ((bytes[offset + i] & 0xFF) != signature[i]) {
				return false;
			}
		}
		return true;
	}

}
