package com.eduaccess.bridgeaccess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.eduaccess.bridgeaccess.dto.DownloadRequest;
import com.eduaccess.bridgeaccess.dto.DownloadView;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.repository.DownloadRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * This device's download records (API doc section 6). The client downloads the parts itself
 * and records the result here; recording the same thing again changes nothing.
 */
@Service
public class DownloadRecordService {

	static final Set<String> STATUSES = Set.of("started", "completed", "failed");

	private final DownloadRepository repository;

	private final ObjectMapper objectMapper;

	public DownloadRecordService(DownloadRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	public DownloadView record(UUID deviceId, UUID packageId, DownloadRequest request) {
		List<Detail> errors = new ArrayList<>();
		String status = request == null ? null : request.status();
		if (status == null) {
			errors.add(new Detail("status", "REQUIRED", "This value is required."));
		}
		else if (!STATUSES.contains(status)) {
			errors.add(new Detail("status", "INVALID_VALUE", "Use started, completed, or failed."));
		}
		Long size = request == null ? null : request.sizeBytes();
		if (size == null) {
			errors.add(new Detail("sizeBytes", "REQUIRED", "This value is required."));
		}
		else if (size < 0 || size > Integer.MAX_VALUE) {
			errors.add(new Detail("sizeBytes", "OUT_OF_RANGE", "Use a size from 0 to " + Integer.MAX_VALUE + "."));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return this.repository.upsert(deviceId, packageId, status, size.intValue(), request.downloadedAt())
			.orElseThrow(ApiException::notFound);
	}

	/** Always succeeds: removing what is not there (or no longer there) is a no-op. */
	public void remove(UUID deviceId, UUID packageId) {
		this.repository.markRemoved(deviceId, packageId);
	}

	public PageResponse<DownloadView> list(UUID deviceId, Integer limit, String cursor) {
		List<Detail> errors = new ArrayList<>();
		Paging paging = Paging.parse(limit, cursor, this.objectMapper, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		List<DownloadView> rows = this.repository.findPage(deviceId, paging.afterTime(), paging.afterId(),
				paging.fetchSize());
		return paging.page(rows, DownloadView::downloadedAt, DownloadView::packageId, this.objectMapper);
	}

}
