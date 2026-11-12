package com.eduaccess.bridgeaccess.dto;

import java.util.List;

/** Keyset pagination envelope. {@code nextCursor} is {@code null} on the last page. */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
