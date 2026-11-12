package com.eduaccess.bridgeaccess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eduaccess.bridgeaccess.dto.PackageBundle;
import com.eduaccess.bridgeaccess.dto.PackageSummary;
import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.BundleHead;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.FeedQuery;

import tools.jackson.databind.ObjectMapper;

/** Public catalogue: the Explore feed and offline bundles. */
@Service
public class PackageService {

	public static final int DEFAULT_LIMIT = 20;
	public static final int MAX_LIMIT = 50;
	public static final int MIN_QUERY_LENGTH = 2;
	public static final int MAX_QUERY_LENGTH = 100;

	static final Set<String> SUBJECTS = Set.of("Science", "Health", "Math", "Technology", "Language", "History",
			"Other");
	static final Set<String> RESOURCE_TYPES = Set.of("text", "video");
	static final Set<String> PACKAGE_TYPES = Set.of("text_only", "with_visual");
	static final Set<String> SORTS = Set.of("latest_published");

	/** Raw query parameters of {@code GET /packages}; validated by {@link #listPublished}. */
	public record ListParams(String q, String subject, String resourceType, String packageType, String sort,
			boolean excludeMine, Integer limit, String cursor) {
	}

	private final CatalogueRepository repository;

	private final ObjectMapper objectMapper;

	public PackageService(CatalogueRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	// Single query, so no transaction: validation errors must not borrow a DB connection.
	public PageResponse<PackageSummary> listPublished(UUID deviceId, ListParams params) {
		List<Detail> errors = new ArrayList<>();

		String search = blankToNull(params.q());
		if (search != null) {
			if (search.length() < MIN_QUERY_LENGTH) {
				errors.add(new Detail("q", "TOO_SHORT", "Enter at least " + MIN_QUERY_LENGTH + " characters."));
			}
			else if (search.length() > MAX_QUERY_LENGTH) {
				errors.add(new Detail("q", "TOO_LONG", "Enter at most " + MAX_QUERY_LENGTH + " characters."));
			}
		}
		String subject = oneOf("subject", params.subject(), SUBJECTS, errors);
		String resourceType = oneOf("resourceType", params.resourceType(), RESOURCE_TYPES, errors);
		String packageType = oneOf("packageType", params.packageType(), PACKAGE_TYPES, errors);
		oneOf("sort", params.sort(), SORTS, errors);

		int limit = params.limit() == null ? DEFAULT_LIMIT : params.limit();
		if (limit < 1 || limit > MAX_LIMIT) {
			errors.add(new Detail("limit", "OUT_OF_RANGE", "Use a value from 1 to " + MAX_LIMIT + "."));
		}

		FeedCursor after = null;
		String rawCursor = blankToNull(params.cursor());
		if (rawCursor != null) {
			after = FeedCursor.decode(rawCursor, objectMapper);
			if (after == null) {
				errors.add(new Detail("cursor", "INVALID_CURSOR", "This page link is no longer valid."));
			}
		}

		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		String searchText = search == null ? null : search.toLowerCase(Locale.ROOT);
		// Fetch one extra row to learn whether another page exists.
		List<PackageSummary> rows = repository.findFeed(new FeedQuery(deviceId, searchText, subject, resourceType,
				packageType, params.excludeMine(), after, limit + 1));

		if (rows.size() <= limit) {
			return new PageResponse<>(rows, null);
		}
		List<PackageSummary> page = rows.subList(0, limit);
		PackageSummary last = page.get(limit - 1);
		String next = new FeedCursor(last.publishedAt(), last.id()).encode(objectMapper);
		return new PageResponse<>(List.copyOf(page), next);
	}

	@Transactional(readOnly = true)
	public PackageBundle getBundle(UUID packageId) {
		BundleHead head = repository.findBundleHead(packageId).orElseThrow(ApiException::notFound);
		List<PackageBundle.Question> questions = repository.findQuestions(packageId);
		List<PackageBundle.Image> images = repository.findImages(packageId);
		return new PackageBundle(PackageBundle.SCHEMA_VERSION, head.info(), head.content(),
				new PackageBundle.Quiz(questions), images);
	}

	private static String oneOf(String field, String value, Set<String> allowed, List<Detail> errors) {
		String v = blankToNull(value);
		if (v != null && !allowed.contains(v)) {
			errors.add(new Detail(field, "INVALID_VALUE", "Choose one of: " + String.join(", ", allowed.stream()
				.sorted()
				.toList()) + "."));
			return null;
		}
		return v;
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
