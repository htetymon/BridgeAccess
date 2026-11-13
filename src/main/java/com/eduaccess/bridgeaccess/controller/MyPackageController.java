package com.eduaccess.bridgeaccess.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eduaccess.bridgeaccess.dto.MyPackage;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.MyPackageService;

/**
 * A contributor's own packages (API doc, section 8): My Contributions list, status for
 * polling, retry and delete.
 * Ownership is the {@code X-Device-Id}; anything not owned by it is 404.
 */
@RestController
@RequestMapping("/me/packages")
public class MyPackageController {

	/** {@code 202} body of a retry. */
	public record RetryResponse(UUID id, String status) {
	}

	private final MyPackageService service;

	public MyPackageController(MyPackageService service) {
		this.service = service;
	}

	/** My Contributions: every status except deleted, newest first. */
	@GetMapping
	public ResponseEntity<PageResponse<MyPackage>> list(
			@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(this.service.list(deviceId, status, limit, cursor));
	}

	/**
	 * Polled after create and retry. {@code private, no-cache}: shared caches never keep it
	 * and the browser always asks again, but an unchanged status comes back as a bodiless
	 * {@code 304} through the ETag filter, which matters on slow connections.
	 */
	@GetMapping("/{packageId}")
	public ResponseEntity<MyPackage> get(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(this.service.get(deviceId, packageId));
	}

	@PostMapping("/{packageId}/retry")
	public ResponseEntity<RetryResponse> retry(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		this.service.retry(deviceId, packageId);
		return ResponseEntity.accepted().body(new RetryResponse(packageId, "processing"));
	}

	@DeleteMapping("/{packageId}")
	public ResponseEntity<Void> delete(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@PathVariable UUID packageId) {
		this.service.delete(deviceId, packageId);
		return ResponseEntity.noContent().build();
	}

}
