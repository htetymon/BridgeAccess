package com.eduaccess.bridgeaccess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.eduaccess.bridgeaccess.config.ContributionConfig.ContributionProperties;
import com.eduaccess.bridgeaccess.dto.MyPackage;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.error.ErrorCode;
import com.eduaccess.bridgeaccess.repository.ContributionRepository;
import com.eduaccess.bridgeaccess.repository.MyPackageRepository;
import com.eduaccess.bridgeaccess.repository.MyPackageRepository.OwnedPackage;
import com.eduaccess.bridgeaccess.repository.MyPackageRepository.PageQuery;
import com.eduaccess.bridgeaccess.service.generation.GenerationDispatcher;

import tools.jackson.databind.ObjectMapper;

/**
 * A contributor's own packages: My Contributions list, status, retry and delete (FR-5.7,
 * FR-5.8). A package that is missing, deleted or belongs to another device is always
 * {@code 404 NOT_FOUND}, so the API never reveals that someone else's package exists.
 */
@Service
public class MyPackageService {

	private static final Logger log = LoggerFactory.getLogger(MyPackageService.class);

	static final Set<String> STATUSES = Set.of("processing", "published", "failed");

	static final String RETRIES_USED_UP = "This package can't be retried again. Please create it again.";

	private final MyPackageRepository repository;

	private final ContributionRepository contributionRepository;

	private final GenerationDispatcher generationDispatcher;

	private final DeletedPackageCleaner cleaner;

	private final ContributionProperties properties;

	private final ObjectMapper objectMapper;

	public MyPackageService(MyPackageRepository repository, ContributionRepository contributionRepository,
			GenerationDispatcher generationDispatcher, DeletedPackageCleaner cleaner, ContributionProperties properties,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.contributionRepository = contributionRepository;
		this.generationDispatcher = generationDispatcher;
		this.cleaner = cleaner;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	/**
	 * This device's packages, newest first. The cursor uses the same opaque format as the
	 * Explore feed, holding {@code createdAt} instead of {@code publishedAt}.
	 */
	public PageResponse<MyPackage> list(UUID deviceId, String status, Integer limit, String cursor) {
		List<Detail> errors = new ArrayList<>();
		String wantedStatus = blankToNull(status);
		if (wantedStatus != null && !STATUSES.contains(wantedStatus)) {
			errors.add(new Detail("status", "INVALID_VALUE", "Use processing, published, or failed."));
		}
		int size = limit == null ? PackageService.DEFAULT_LIMIT : limit;
		if (size < 1 || size > PackageService.MAX_LIMIT) {
			errors.add(new Detail("limit", "OUT_OF_RANGE", "Use a value from 1 to " + PackageService.MAX_LIMIT + "."));
		}
		FeedCursor after = null;
		if (blankToNull(cursor) != null) {
			after = FeedCursor.decode(cursor.strip(), this.objectMapper);
			if (after == null) {
				errors.add(new Detail("cursor", "INVALID_CURSOR", "This page link is no longer valid."));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		// One extra row tells whether another page exists.
		List<MyPackage> rows = this.repository.findPage(new PageQuery(deviceId, wantedStatus,
				after == null ? null : after.publishedAt(), after == null ? null : after.id(), size + 1));
		if (rows.size() <= size) {
			return new PageResponse<>(rows, null);
		}
		List<MyPackage> page = List.copyOf(rows.subList(0, size));
		MyPackage last = page.get(size - 1);
		return new PageResponse<>(page, new FeedCursor(last.createdAt(), last.id()).encode(this.objectMapper));
	}

	public MyPackage get(UUID deviceId, UUID packageId) {
		return owned(deviceId, packageId).view();
	}

	/**
	 * Re-runs the AI job for a failed package, reusing its stored content and images. Returns
	 * once it is queued; the client polls {@link #get} as after creating. Refused once the
	 * package has used its Gemini budget ({@code maxAiAttempts}).
	 */
	public void retry(UUID deviceId, UUID packageId) {
		OwnedPackage pkg = owned(deviceId, packageId);
		if (!"failed".equals(pkg.view().status())) {
			throw new ApiException(ErrorCode.NOT_RETRYABLE);
		}
		int maxAttempts = this.properties.maxAiAttempts();
		if (pkg.attemptCount() >= maxAttempts) {
			throw new ApiException(ErrorCode.NOT_RETRYABLE, RETRIES_USED_UP);
		}
		boolean reset;
		try {
			reset = this.repository.resetForRetry(deviceId, packageId, maxAttempts);
		}
		catch (DuplicateKeyException ex) {
			// Since this one failed, another live package took the same link (uq_lp_live_link).
			this.contributionRepository.findLiveByLink(pkg.resourceLink()).ifPresent(owner -> {
				throw ApiException.duplicateLink(owner.packageId(), owner.deviceId().equals(deviceId));
			});
			throw ex;
		}
		if (!reset) {
			// Changed between the read and the update: deleted (404) or already retried (409).
			owned(deviceId, packageId);
			throw new ApiException(ErrorCode.NOT_RETRYABLE);
		}
		// Committed (single statement). Never throws; a full queue leaves it for the sweeper.
		this.generationDispatcher.submit(packageId);
		log.info("Package {} queued for retry ({} of {} AI attempts used)", packageId, pkg.attemptCount(),
				maxAttempts);
	}

	/**
	 * Soft-deletes the package, then removes its files from storage. File removal happens
	 * after the delete is committed and never fails the request: if storage is down, the
	 * package is still deleted for everyone and {@link DeletedPackageCleaner} retries later.
	 */
	public void delete(UUID deviceId, UUID packageId) {
		if (!this.repository.softDelete(deviceId, packageId)) {
			throw ApiException.notFound();
		}
		this.cleaner.clean(packageId);
	}

	private OwnedPackage owned(UUID deviceId, UUID packageId) {
		return this.repository.findOwned(deviceId, packageId).orElseThrow(ApiException::notFound);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
