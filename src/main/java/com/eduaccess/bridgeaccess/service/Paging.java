package com.eduaccess.bridgeaccess.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code limit} and {@code cursor} of the {@code /me/...} lists. The cursor is the same
 * opaque {@link FeedCursor} as the Explore feed, holding the list's own sort timestamp
 * ({@code lastOpenedAt}, {@code attemptedAt}, {@code downloadedAt} or {@code bookmarkedAt})
 * and the row's ID as the tiebreak.
 *
 * @param fetchSize {@code limit + 1}: the extra row tells whether another page exists
 * @param after keyset position, or {@code null} for the first page
 */
record Paging(int limit, int fetchSize, FeedCursor after) {

	/** Adds any problems to {@code errors}; the result is only meaningful when none were added. */
	static Paging parse(Integer limit, String cursor, ObjectMapper mapper, List<Detail> errors) {
		int size = limit == null ? PackageService.DEFAULT_LIMIT : limit;
		if (size < 1 || size > PackageService.MAX_LIMIT) {
			errors.add(new Detail("limit", "OUT_OF_RANGE", "Use a value from 1 to " + PackageService.MAX_LIMIT + "."));
		}
		FeedCursor after = null;
		if (cursor != null && !cursor.isBlank()) {
			after = FeedCursor.decode(cursor.strip(), mapper);
			if (after == null) {
				errors.add(new Detail("cursor", "INVALID_CURSOR", "This page link is no longer valid."));
			}
		}
		return new Paging(size, size + 1, after);
	}

	Instant afterTime() {
		return this.after == null ? null : this.after.publishedAt();
	}

	UUID afterId() {
		return this.after == null ? null : this.after.id();
	}

	/** Trims the extra row and builds the next cursor from the last row kept. */
	<T> PageResponse<T> page(List<T> rows, Function<T, Instant> time, Function<T, UUID> id, ObjectMapper mapper) {
		if (rows.size() <= this.limit) {
			return new PageResponse<>(List.copyOf(rows), null);
		}
		List<T> page = List.copyOf(rows.subList(0, this.limit));
		T last = page.get(this.limit - 1);
		return new PageResponse<>(page, new FeedCursor(time.apply(last), id.apply(last)).encode(mapper));
	}

}
