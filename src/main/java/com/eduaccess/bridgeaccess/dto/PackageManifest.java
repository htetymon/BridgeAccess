package com.eduaccess.bridgeaccess.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /packages/{id}/manifest}: every part a device must store for a package to count
 * as downloaded (FR-4.4), with its size and ETag, so the client can show progress, download
 * part by part, skip parts it already has, and resume an image with {@code Range}.
 *
 * @param version changes whenever any part changes (it is the manifest's ETag, unquoted)
 * @param totalBytes sum of the parts' sizes, as stored (the bundle is gzipped in transit, so
 * the bytes on the wire are fewer)
 */
public record PackageManifest(UUID packageId, String version, long totalBytes, List<Part> parts) {

	/**
	 * @param kind {@code bundle} (the text, quiz and image list as JSON) or {@code image}
	 * @param id the package ID for the bundle, the image ID for an image
	 * @param etag the part's ETag, exactly as its URL returns it: a stored part whose ETag
	 * matches is current and need not be downloaded again
	 */
	public record Part(String kind, UUID id, String url, String mimeType, long sizeBytes, String etag) {
	}

}
