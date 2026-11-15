package com.eduaccess.bridgeaccess.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/** Small JDBC conversions shared by the learner data repositories. */
final class Sql {

	private Sql() {
	}

	/** Bind value for a {@code timestamptz} parameter (null stays null). */
	static OffsetDateTime timestamp(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	static List<String> textArray(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		if (array == null) {
			return List.of();
		}
		try {
			return List.copyOf(Arrays.asList((String[]) array.getArray()));
		}
		finally {
			array.free();
		}
	}

}
