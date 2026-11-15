package com.eduaccess.bridgeaccess.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.eduaccess.bridgeaccess.dto.BookmarkView;
import com.eduaccess.bridgeaccess.dto.PackageSummary;

/**
 * {@code app.bookmarks}, keyed by device and package (primary key), so adding twice or
 * removing something absent changes nothing.
 */
@Repository
public class BookmarkRepository {

	public enum AddResult {
		ADDED, ALREADY_BOOKMARKED, NOT_AVAILABLE
	}

	private final NamedParameterJdbcTemplate jdbc;

	public BookmarkRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Bookmarks a published, not deleted package. An existing bookmark counts as success even if
	 * the package has since been deleted, so a replay of an earlier add is still fine.
	 */
	public AddResult add(UUID deviceId, UUID packageId) {
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId).addValue("packageId", packageId);
		int inserted = this.jdbc.update("""
				insert into app.bookmarks (device_id, package_id)
				select cast(:deviceId as uuid), lp.id
				  from app.learning_packages lp
				 where lp.id = :packageId and lp.status = 'published' and lp.deleted_at is null
				on conflict (device_id, package_id) do nothing""", params);
		if (inserted == 1) {
			return AddResult.ADDED;
		}
		Integer existing = this.jdbc.queryForObject(
				"select count(*) from app.bookmarks where device_id = :deviceId and package_id = :packageId", params,
				Integer.class);
		return existing != null && existing > 0 ? AddResult.ALREADY_BOOKMARKED : AddResult.NOT_AVAILABLE;
	}

	public void remove(UUID deviceId, UUID packageId) {
		this.jdbc.update("delete from app.bookmarks where device_id = :deviceId and package_id = :packageId",
				new MapSqlParameterSource("deviceId", deviceId).addValue("packageId", packageId));
	}

	/**
	 * Newest bookmark first, keyset on {@code (created_at, package_id)} ({@code idx_bm_device_recent}).
	 * Bookmarks of packages that were unpublished or deleted later are left out.
	 */
	public List<BookmarkView> findPage(UUID deviceId, Instant afterTime, UUID afterId, int fetchSize) {
		StringBuilder sql = new StringBuilder("""
				select b.created_at as bookmarked_at, lp.id, lp.title, lp.subject, lp.resource_type, lp.package_type,
				       lp.package_size_bytes, lp.published_at,
				       (lp.device_id = :deviceId) as is_own,
				       exists (select 1 from app.downloads d
				                where d.package_id = lp.id and d.device_id = :deviceId
				                  and d.status = 'completed' and d.removed_at is null) as is_downloaded
				  from app.bookmarks b
				  join app.learning_packages lp on lp.id = b.package_id
				 where b.device_id = :deviceId and lp.status = 'published' and lp.deleted_at is null""");
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId).addValue("fetchSize", fetchSize);
		if (afterTime != null) {
			sql.append(" and (b.created_at, b.package_id) < (:afterTime, :afterId)");
			params.addValue("afterTime", Sql.timestamp(afterTime)).addValue("afterId", afterId);
		}
		sql.append(" order by b.created_at desc, b.package_id desc limit :fetchSize");
		return this.jdbc.query(sql.toString(), params,
				(rs, i) -> new BookmarkView(Sql.instant(rs, "bookmarked_at"),
						new PackageSummary(rs.getObject("id", UUID.class), rs.getString("title"),
								rs.getString("subject"), rs.getString("resource_type"), rs.getString("package_type"),
								rs.getInt("package_size_bytes"), Sql.instant(rs, "published_at"),
								rs.getBoolean("is_downloaded"), rs.getBoolean("is_own"))));
	}

}
