package com.eduaccess.bridgeaccess.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.eduaccess.bridgeaccess.dto.PackageBundle;
import com.eduaccess.bridgeaccess.dto.PackageSummary;
import com.eduaccess.bridgeaccess.service.FeedCursor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only queries for the public catalogue (published, non-deleted packages). Plain SQL
 * because the schema relies on Postgres enums, arrays, jsonb and partial keyset indexes.
 */
@Repository
public class CatalogueRepository {

	/** Filters for the Explore feed. Null fields are not applied. */
	public record FeedQuery(UUID deviceId, String searchText, String subject, String resourceType,
			String packageType, boolean excludeMine, FeedCursor after, int fetchSize) {
	}

	/** Package metadata and text content, before quiz and images are attached. */
	public record BundleHead(PackageBundle.Info info, PackageBundle.Content content) {
	}

	private static final TypeReference<List<PackageBundle.KeyConcept>> KEY_CONCEPTS = new TypeReference<>() {
	};

	private static final String VISIBLE = "lp.status = 'published' and lp.deleted_at is null";

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public CatalogueRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public List<PackageSummary> findFeed(FeedQuery query) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("deviceId", query.deviceId())
			.addValue("fetchSize", query.fetchSize());
		StringBuilder sql = new StringBuilder("""
				select lp.id, lp.title, lp.subject, lp.resource_type, lp.package_type,
				       lp.package_size_bytes, lp.published_at,
				       (lp.device_id = :deviceId) as is_own,
				       exists (select 1 from app.downloads d
				                where d.package_id = lp.id and d.device_id = :deviceId
				                  and d.status = 'completed' and d.removed_at is null) as is_downloaded
				  from app.learning_packages lp
				 where """).append(VISIBLE);

		if (query.searchText() != null) {
			// search_text is lower(title || original_title || author), trigram-indexed.
			sql.append(" and lp.search_text like :pattern");
			params.addValue("pattern", "%" + escapeLike(query.searchText()) + "%");
		}
		if (query.subject() != null) {
			sql.append(" and lp.subject = :subject");
			params.addValue("subject", query.subject());
		}
		if (query.resourceType() != null) {
			sql.append(" and lp.resource_type = cast(:resourceType as app.resource_type)");
			params.addValue("resourceType", query.resourceType());
		}
		if (query.packageType() != null) {
			sql.append(" and lp.package_type = cast(:packageType as app.package_type)");
			params.addValue("packageType", query.packageType());
		}
		if (query.excludeMine()) {
			sql.append(" and lp.device_id <> :deviceId");
		}
		if (query.after() != null) {
			sql.append(" and (lp.published_at, lp.id) < (:afterPublishedAt, :afterId)");
			params.addValue("afterPublishedAt", OffsetDateTime.ofInstant(query.after().publishedAt(), ZoneOffset.UTC));
			params.addValue("afterId", query.after().id());
		}
		sql.append(" order by lp.published_at desc, lp.id desc limit :fetchSize");

		return jdbc.query(sql.toString(), params,
				(rs, i) -> new PackageSummary(rs.getObject("id", UUID.class), rs.getString("title"),
						rs.getString("subject"), rs.getString("resource_type"), rs.getString("package_type"),
						rs.getInt("package_size_bytes"), instant(rs, "published_at"), rs.getBoolean("is_downloaded"),
						rs.getBoolean("is_own")));
	}

	public Optional<BundleHead> findBundleHead(UUID packageId) {
		String sql = """
				select lp.id, lp.title, lp.subject, lp.resource_type, lp.package_type,
				       lp.original_title, lp.author, lp.resource_link,
				       lp.package_size_bytes, lp.published_at,
				       pc.overview, pc.key_takeaways, pc.summary,
				       cast(pc.key_concepts as text) as key_concepts
				  from app.learning_packages lp
				  join app.package_content pc on pc.package_id = lp.id
				 where lp.id = :id and """ + VISIBLE;
		List<BundleHead> rows = jdbc.query(sql, new MapSqlParameterSource("id", packageId), (rs, i) -> {
			String packageType = rs.getString("package_type");
			PackageBundle.Info info = new PackageBundle.Info(rs.getObject("id", UUID.class), rs.getString("title"),
					rs.getString("subject"), rs.getString("resource_type"), packageType, stepsFor(packageType),
					new PackageBundle.Original(rs.getString("original_title"), rs.getString("author"),
							rs.getString("resource_link")),
					rs.getInt("package_size_bytes"), instant(rs, "published_at"));
			PackageBundle.Content content = new PackageBundle.Content(rs.getString("overview"),
					textArray(rs, "key_takeaways"), rs.getString("summary"),
					objectMapper.readValue(rs.getString("key_concepts"), KEY_CONCEPTS));
			return new BundleHead(info, content);
		});
		return rows.stream().findFirst();
	}

	public List<PackageBundle.Question> findQuestions(UUID packageId) {
		String sql = """
				select id, position, question, options, correct_index, category, explanation
				  from app.quiz_questions
				 where package_id = :id
				 order by position""";
		return jdbc.query(sql, new MapSqlParameterSource("id", packageId),
				(rs, i) -> new PackageBundle.Question(rs.getObject("id", UUID.class), rs.getInt("position"),
						rs.getString("question"), textArray(rs, "options"), rs.getInt("correct_index"),
						rs.getString("category"), rs.getString("explanation")));
	}

	public List<PackageBundle.Image> findImages(UUID packageId) {
		String sql = """
				select id, source, position, mime_type, size_bytes, alt_text
				  from app.package_images
				 where package_id = :id
				 order by position""";
		return jdbc.query(sql, new MapSqlParameterSource("id", packageId), (rs, i) -> {
			UUID imageId = rs.getObject("id", UUID.class);
			return new PackageBundle.Image(imageId, rs.getString("source"), rs.getInt("position"),
					rs.getString("mime_type"), rs.getInt("size_bytes"), rs.getString("alt_text"),
					"/api/v1/packages/" + packageId + "/images/" + imageId);
		});
	}

	/** Learning steps the client renders, per API section 3. */
	static List<String> stepsFor(String packageType) {
		return "with_visual".equals(packageType) ? List.of("summary", "key_concepts", "diagram", "quiz")
				: List.of("summary", "key_concepts", "quiz");
	}

	/** Escapes LIKE wildcards; Postgres uses backslash as the default LIKE escape. */
	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static List<String> textArray(ResultSet rs, String column) throws SQLException {
		java.sql.Array array = rs.getArray(column);
		if (array == null) {
			return List.of();
		}
		try {
			return Arrays.asList((String[]) array.getArray());
		}
		finally {
			array.free();
		}
	}

}
