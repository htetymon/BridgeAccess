package com.eduaccess.bridgeaccess.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.eduaccess.bridgeaccess.dto.PackageResponse;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.ContributionService;
import com.eduaccess.bridgeaccess.service.ContributionService.CreatedPackage;

/** Contribution endpoints (API doc, section 8). */
@RestController
@RequestMapping("/packages")
public class ContributionController {

	private final ContributionService contributionService;

	public ContributionController(ContributionService contributionService) {
		this.contributionService = contributionService;
	}

	/**
	 * Accepts the package and returns 202 at once; the AI job runs afterwards and the client
	 * polls {@code Location}. {@code data} is read as text and parsed by the validator, so a
	 * part sent without {@code Content-Type: application/json} still works and malformed
	 * JSON gets a field-level 400.
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<PackageResponse> create(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestPart(name = "data", required = false) String data,
			@RequestPart(name = "images", required = false) List<MultipartFile> images) {
		CreatedPackage created = this.contributionService.create(deviceId, data, images);
		return ResponseEntity.accepted()
			.location(URI.create("/api/v1/me/packages/" + created.id()))
			.body(PackageResponse.processing(created.id(), created.createdAt()));
	}

}
