package com.eduaccess.bridgeaccess.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.eduaccess.bridgeaccess.dto.PackageBundle;
import com.eduaccess.bridgeaccess.dto.PackageManifest;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.ImageFile;
import com.eduaccess.bridgeaccess.storage.ImageStore;
import com.eduaccess.bridgeaccess.storage.ImageStore.ImageNotFoundException;

import tools.jackson.databind.ObjectMapper;

/**
 * Downloading a package part by part (FR-4.3, FR-4.4): the manifest, the bundle, and each
 * image. Only published, not deleted packages can be downloaded; anything else is 404.
 *
 * <p>
 * Every part has a strong ETag derived from what it is made of, so the manifest can tell the
 * client each part's ETag before it is fetched, and a part the client already holds can be
 * skipped (or revalidated with a 304) instead of downloaded again:
 * <ul>
 * <li>bundle: a hash of its exact JSON bytes;</li>
 * <li>image: a hash of its ID, storage path and size. An image's bytes never change at a
 * given storage path (an optimised image gets a new path), so this changes exactly when the
 * bytes do, and a 304 can be answered without reading the file from storage.</li>
 * </ul>
 */
@Service
public class PackageDownloadService {

	private static final Logger log = LoggerFactory.getLogger(PackageDownloadService.class);

	/** The bundle as sent: its JSON bytes and ETag. */
	public record RenderedBundle(byte[] json, String etag) {
	}

	private final PackageService packageService;

	private final CatalogueRepository repository;

	private final ImageStore imageStore;

	private final ObjectMapper objectMapper;

	public PackageDownloadService(PackageService packageService, CatalogueRepository repository, ImageStore imageStore,
			ObjectMapper objectMapper) {
		this.packageService = packageService;
		this.repository = repository;
		this.imageStore = imageStore;
		this.objectMapper = objectMapper;
	}

	/**
	 * Serialised once here rather than by the framework, so the ETag and the size in the
	 * manifest describe exactly the bytes the bundle endpoint sends.
	 */
	public RenderedBundle bundle(UUID packageId) {
		PackageBundle bundle = this.packageService.getBundle(packageId);
		byte[] json = this.objectMapper.writeValueAsBytes(bundle);
		return new RenderedBundle(json, etag("b", json));
	}

	public PackageManifest manifest(UUID packageId) {
		RenderedBundle bundle = bundle(packageId); // 404 unless published and not deleted
		List<PackageManifest.Part> parts = new ArrayList<>();
		parts.add(new PackageManifest.Part("bundle", packageId, "/api/v1/packages/" + packageId + "/bundle",
				"application/json", bundle.json().length, bundle.etag()));
		for (ImageFile image : this.repository.findImageFiles(packageId)) {
			parts.add(new PackageManifest.Part("image", image.id(), imageUrl(packageId, image.id()), image.mimeType(),
					image.sizeBytes(), imageEtag(image)));
		}
		long total = parts.stream().mapToLong(PackageManifest.Part::sizeBytes).sum();
		String version = hash(String.join(",", parts.stream().map(PackageManifest.Part::etag).toList())
			.getBytes(StandardCharsets.UTF_8));
		return new PackageManifest(packageId, version, total, List.copyOf(parts));
	}

	/** The manifest's own ETag: its version, quoted. */
	public static String manifestEtag(PackageManifest manifest) {
		return "\"m-" + manifest.version() + "\"";
	}

	/** Metadata only: enough to answer a conditional request without touching storage. */
	public ImageFile imageFile(UUID packageId, UUID imageId) {
		return this.repository.findImageFile(packageId, imageId).orElseThrow(ApiException::notFound);
	}

	public byte[] imageBytes(ImageFile image) {
		try {
			return this.imageStore.get(image.storagePath());
		}
		catch (ImageNotFoundException ex) {
			// A row without its file: a storage problem, not the client's. Logged loudly, 404 to the client.
			log.error("Image {} has no file at {}", image.id(), image.storagePath());
			throw ApiException.notFound();
		}
	}

	public static String imageEtag(ImageFile image) {
		return etag("i", (image.id() + "|" + image.storagePath() + "|" + image.sizeBytes())
			.getBytes(StandardCharsets.UTF_8));
	}

	static String imageUrl(UUID packageId, UUID imageId) {
		return "/api/v1/packages/" + packageId + "/images/" + imageId;
	}

	/** Strong ETag: quoted, prefixed by part kind, 128 bits of SHA-256. */
	private static String etag(String kind, byte[] content) {
		return "\"" + kind + "-" + hash(content) + "\"";
	}

	private static String hash(byte[] content) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
			return HexFormat.of().formatHex(digest, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
