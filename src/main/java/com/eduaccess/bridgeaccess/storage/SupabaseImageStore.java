package com.eduaccess.bridgeaccess.storage;

import java.util.Arrays;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * {@link ImageStore} on a private Supabase Storage bucket, through the Storage REST API
 * ({@code /storage/v1/object/...}). Authenticates with the project's secret key (or legacy
 * {@code service_role} key), which bypasses row level security, so it must stay on the
 * server. The key is sent only in headers set on the {@link RestClient}, never in a URL, a
 * log line or an exception message.
 *
 * <p>
 * Objects get a one-year {@code cache-control}: a path is never reused for different bytes
 * (an optimised image gets a new path), so caches can keep them.
 */
public class SupabaseImageStore implements ImageStore {

	static final String CACHE_SECONDS = "31536000";

	private final RestClient restClient;

	private final String bucket;

	/**
	 * @param restClient based at the project URL, with the {@code apikey} and
	 * {@code Authorization} headers set (see {@code StorageConfig})
	 */
	public SupabaseImageStore(RestClient restClient, String bucket) {
		this.restClient = restClient;
		this.bucket = bucket;
	}

	@Override
	public void put(String path, byte[] bytes, String mimeType) {
		try {
			this.restClient.post()
				.uri(uri -> object(uri, path))
				.contentType(MediaType.parseMediaType(mimeType))
				.header("x-upsert", "true")
				.header("cache-control", "max-age=" + CACHE_SECONDS)
				.body(bytes)
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientResponseException ex) {
			throw new StorageException("upload", path, ex);
		}
	}

	@Override
	public byte[] get(String path) {
		try {
			byte[] bytes = this.restClient.get().uri(uri -> object(uri, path)).retrieve().body(byte[].class);
			return bytes == null ? new byte[0] : bytes;
		}
		catch (RestClientResponseException ex) {
			if (isNotFound(ex)) {
				throw new ImageNotFoundException(path);
			}
			throw new StorageException("download", path, ex);
		}
	}

	@Override
	public void delete(String path) {
		try {
			this.restClient.delete().uri(uri -> object(uri, path)).retrieve().toBodilessEntity();
		}
		catch (RestClientResponseException ex) {
			if (!isNotFound(ex)) {
				throw new StorageException("delete", path, ex);
			}
		}
	}

	/** One path segment per part, so '/' separates folders and nothing else is unescaped. */
	private java.net.URI object(UriBuilder uri, String path) {
		String[] parts = path.split("/");
		if (path.isBlank() || Arrays.stream(parts).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
			throw new IllegalArgumentException("Invalid storage path: " + path);
		}
		return uri.pathSegment("storage", "v1", "object", this.bucket).pathSegment(parts).build();
	}

	/**
	 * Storage answers a missing object with 404, or (older versions) with 400 and
	 * {@code "error":"not_found"} in the body.
	 */
	private static boolean isNotFound(RestClientResponseException ex) {
		HttpStatusCode status = ex.getStatusCode();
		return status.value() == 404
				|| (status.value() == 400 && ex.getResponseBodyAsString().toLowerCase().contains("not_found"));
	}

	/** Storage refused a request. Carries Supabase's error body (no secrets) for the log. */
	public static class StorageException extends RuntimeException {

		StorageException(String action, String path, RestClientResponseException cause) {
			super("Supabase Storage " + action + " of " + path + " failed: " + cause.getStatusCode().value() + " "
					+ abbreviate(cause.getResponseBodyAsString()), cause);
		}

		private static String abbreviate(String body) {
			String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").strip();
			return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
		}

	}

}
