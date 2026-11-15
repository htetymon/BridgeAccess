package com.eduaccess.bridgeaccess.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eduaccess.bridgeaccess.dto.BookmarkView;
import com.eduaccess.bridgeaccess.dto.DownloadRequest;
import com.eduaccess.bridgeaccess.dto.DownloadView;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.dto.ProgressRequest;
import com.eduaccess.bridgeaccess.dto.ProgressView;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.BookmarkService;
import com.eduaccess.bridgeaccess.service.DownloadRecordService;
import com.eduaccess.bridgeaccess.service.ProgressService;

/**
 * Learner data keyed by {@code X-Device-Id} (API doc sections 6 and 7): progress, download
 * records and bookmarks. Every write is idempotent, so the client can resend it after a lost
 * response. Reads are for restoring the device's state; the app works from local data.
 */
@RestController
@RequestMapping("/me")
public class UserActivityController {

	/** Per-device data: never in shared caches, always revalidated (cheap 304 via the ETag filter). */
	private static final CacheControl PRIVATE = CacheControl.noCache().cachePrivate();

	private final ProgressService progress;

	private final DownloadRecordService downloads;

	private final BookmarkService bookmarks;

	public UserActivityController(ProgressService progress, DownloadRecordService downloads,
			BookmarkService bookmarks) {
		this.progress = progress;
		this.downloads = downloads;
		this.bookmarks = bookmarks;
	}

	// ------------------------------------------------------------------ progress

	@PutMapping("/progress/{packageId}")
	public ProgressView putProgress(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId, @RequestBody ProgressRequest body) {
		return this.progress.upsert(deviceId, packageId, body);
	}

	@GetMapping("/progress")
	public ResponseEntity<PageResponse<ProgressView>> listProgress(
			@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
		return ResponseEntity.ok().cacheControl(PRIVATE).body(this.progress.list(deviceId, limit, cursor));
	}

	@GetMapping("/progress/{packageId}")
	public ResponseEntity<ProgressView> getProgress(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		return ResponseEntity.ok().cacheControl(PRIVATE).body(this.progress.get(deviceId, packageId));
	}

	// ----------------------------------------------------------------- downloads

	@PutMapping("/downloads/{packageId}")
	public DownloadView putDownload(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId, @RequestBody DownloadRequest body) {
		return this.downloads.record(deviceId, packageId, body);
	}

	@DeleteMapping("/downloads/{packageId}")
	public ResponseEntity<Void> deleteDownload(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		this.downloads.remove(deviceId, packageId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/downloads")
	public ResponseEntity<PageResponse<DownloadView>> listDownloads(
			@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
		return ResponseEntity.ok().cacheControl(PRIVATE).body(this.downloads.list(deviceId, limit, cursor));
	}

	// ----------------------------------------------------------------- bookmarks

	@PutMapping("/bookmarks/{packageId}")
	public ResponseEntity<Void> putBookmark(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		this.bookmarks.add(deviceId, packageId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/bookmarks/{packageId}")
	public ResponseEntity<Void> deleteBookmark(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		this.bookmarks.remove(deviceId, packageId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/bookmarks")
	public ResponseEntity<PageResponse<BookmarkView>> listBookmarks(
			@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
		return ResponseEntity.ok().cacheControl(PRIVATE).body(this.bookmarks.list(deviceId, limit, cursor));
	}

}
