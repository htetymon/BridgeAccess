package com.eduaccess.bridgeaccess.controller;

import java.time.Duration;
import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import com.eduaccess.bridgeaccess.dto.PackageDetail;
import com.eduaccess.bridgeaccess.dto.PackageManifest;
import com.eduaccess.bridgeaccess.dto.PackageSummary;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.ImageFile;
import com.eduaccess.bridgeaccess.service.PackageDownloadService;
import com.eduaccess.bridgeaccess.service.PackageDownloadService.RenderedBundle;
import com.eduaccess.bridgeaccess.service.PackageService;

/**
 * Catalogue and download endpoints (API sections 5 and 6). Lists get their ETag from the
 * common filter; the download parts set their own strong ETags (see PackageDownloadService)
 * and answer conditional requests themselves, so a 304 never needs the full response built.
 */
@RestController
@RequestMapping("/packages")
public class PackageController {

	/** Download parts are revalidated each time (cheap 304), never served stale. */
	private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

	/**
	 * Image URLs never change content (a new file gets a new ETag, and the bundle a new
	 * manifest), so the browser may keep them for a year without asking. Private: images of
	 * a deleted package must not linger in shared caches.
	 */
	private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

	private final PackageService packageService;

	private final PackageDownloadService downloads;

	public PackageController(PackageService packageService, PackageDownloadService downloads) {
		this.packageService = packageService;
		this.downloads = downloads;
	}

	@GetMapping
	public PageResponse<PackageSummary> list(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestParam(required = false) String q, @RequestParam(required = false) String subject,
			@RequestParam(required = false) String resourceType, @RequestParam(required = false) String packageType,
			@RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean excludeMine,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
		return packageService.listPublished(deviceId, new PackageService.ListParams(q, subject, resourceType,
				packageType, sort, excludeMine, limit, cursor));
	}

	/**
	 * The package screen before download (FR-7.1). Revalidated each time; an unchanged package
	 * comes back as a bodiless 304 through the ETag filter.
	 */
	@GetMapping("/{packageId}")
	public ResponseEntity<PackageDetail> detail(@PathVariable UUID packageId) {
		return ResponseEntity.ok().cacheControl(REVALIDATE).body(packageService.getDetail(packageId));
	}

	/** Every part to download, with sizes and ETags (FR-4.3, FR-4.4). */
	@GetMapping("/{packageId}/manifest")
	public ResponseEntity<PackageManifest> manifest(@PathVariable UUID packageId, ServletWebRequest request) {
		PackageManifest manifest = downloads.manifest(packageId);
		String etag = PackageDownloadService.manifestEtag(manifest);
		if (request.checkNotModified(etag)) {
			return notModified(etag, REVALIDATE);
		}
		return ResponseEntity.ok().eTag(etag).cacheControl(REVALIDATE).body(manifest);
	}

	@GetMapping("/{packageId}/bundle")
	public ResponseEntity<byte[]> bundle(@PathVariable UUID packageId, ServletWebRequest request) {
		RenderedBundle bundle = downloads.bundle(packageId);
		if (request.checkNotModified(bundle.etag())) {
			return notModified(bundle.etag(), REVALIDATE);
		}
		return ResponseEntity.ok()
			.eTag(bundle.etag())
			.cacheControl(REVALIDATE)
			.contentType(MediaType.APPLICATION_JSON)
			.body(bundle.json());
	}

	/**
	 * Streams an image through the API (same origin, no signed URL to expire mid-resume).
	 * The ETag is known from the database, so a 304 is answered without reading storage.
	 * {@code Range} is handled by Spring for a Resource body: 206 with Content-Range for a
	 * valid range, 416 for one past the end. No {@code If-Range} logic is needed because the
	 * bytes behind an image URL and ETag never change.
	 */
	@GetMapping("/{packageId}/images/{imageId}")
	public ResponseEntity<Resource> image(@PathVariable UUID packageId, @PathVariable UUID imageId,
			ServletWebRequest request) {
		ImageFile file = downloads.imageFile(packageId, imageId);
		String etag = PackageDownloadService.imageEtag(file);
		if (request.checkNotModified(etag)) {
			return notModified(etag, IMMUTABLE);
		}
		// Binary, possibly ranged: the ETag filter must not buffer or re-hash it.
		ShallowEtagHeaderFilter.disableContentCaching(request.getRequest());
		byte[] bytes = downloads.imageBytes(file);
		return ResponseEntity.ok()
			.eTag(etag)
			.cacheControl(IMMUTABLE)
			.contentType(MediaType.parseMediaType(file.mimeType()))
			.body(new ByteArrayResource(bytes));
	}

	/** 304 with the validators and caching rules a 200 would carry (RFC 9110, 15.4.5). */
	private static <T> ResponseEntity<T> notModified(String etag, CacheControl cacheControl) {
		return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cacheControl).build();
	}

}
