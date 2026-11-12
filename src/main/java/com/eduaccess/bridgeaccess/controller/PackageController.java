package com.eduaccess.bridgeaccess.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eduaccess.bridgeaccess.dto.PackageBundle;
import com.eduaccess.bridgeaccess.dto.PackageSummary;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.PackageService;

/** Catalogue endpoints (API section 5). ETag/304 and gzip come from the common plumbing. */
@RestController
@RequestMapping("/packages")
public class PackageController {

	private final PackageService packageService;

	public PackageController(PackageService packageService) {
		this.packageService = packageService;
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

	@GetMapping("/{packageId}/bundle")
	public PackageBundle bundle(@PathVariable UUID packageId) {
		return packageService.getBundle(packageId);
	}

}
