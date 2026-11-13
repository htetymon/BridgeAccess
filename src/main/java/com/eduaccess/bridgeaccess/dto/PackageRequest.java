package com.eduaccess.bridgeaccess.dto;

import java.util.List;

/**
 * The {@code data} part of {@code POST /packages} (API doc, section 8), exactly as sent.
 * Every field is checked by {@code CreatePackageValidator}; nothing here is trusted yet.
 */
public record PackageRequest(String resourceType, String originalTitle, String author, String resourceLink,
		String content, List<String> imageAltTexts) {
}
