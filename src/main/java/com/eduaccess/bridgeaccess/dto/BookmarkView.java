package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One row of {@code GET /me/bookmarks}: when it was bookmarked, and the package's card data. */
public record BookmarkView(Instant bookmarkedAt, @JsonProperty("package") PackageSummary packageSummary) {
}
