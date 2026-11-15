package com.eduaccess.bridgeaccess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.eduaccess.bridgeaccess.dto.BookmarkView;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.repository.BookmarkRepository;
import com.eduaccess.bridgeaccess.repository.BookmarkRepository.AddResult;

import tools.jackson.databind.ObjectMapper;

/** Bookmarks (FR-7.10). Adding and removing are both safe to repeat. */
@Service
public class BookmarkService {

	private final BookmarkRepository repository;

	private final ObjectMapper objectMapper;

	public BookmarkService(BookmarkRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	public void add(UUID deviceId, UUID packageId) {
		if (this.repository.add(deviceId, packageId) == AddResult.NOT_AVAILABLE) {
			throw ApiException.notFound();
		}
	}

	public void remove(UUID deviceId, UUID packageId) {
		this.repository.remove(deviceId, packageId);
	}

	public PageResponse<BookmarkView> list(UUID deviceId, Integer limit, String cursor) {
		List<Detail> errors = new ArrayList<>();
		Paging paging = Paging.parse(limit, cursor, this.objectMapper, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		List<BookmarkView> rows = this.repository.findPage(deviceId, paging.afterTime(), paging.afterId(),
				paging.fetchSize());
		return paging.page(rows, BookmarkView::bookmarkedAt, b -> b.packageSummary().id(), this.objectMapper);
	}

}
