package com.eduaccess.bridgeaccess.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.eduaccess.bridgeaccess.seed.BuiltInPackageSeeder;
import com.eduaccess.bridgeaccess.storage.ImageStore;

import tools.jackson.databind.JsonNode;

/** A fresh catalogue is available immediately and seeding again changes nothing. */
class BuiltInPackageSeederIntegrationTest extends PostgresIntegrationTest {

	private static final String PREFIX = "b0000000-0000-4000-8000-0000000000";

	private static final UUID IMAGE_PACKAGE = UUID.fromString(PREFIX + "01");

	@Autowired
	BuiltInPackageSeeder seeder;

	@Autowired
	ImageStore imageStore;

	@Test
	void seedsSixPublishedPackagesWithAnImageAndAYouTubeSource() throws Exception {
		assertThat(this.seeder.seed()).isEqualTo(6);

		assertThat(count("select count(*) from app.learning_packages where id::text like '" + PREFIX + "%'"))
			.isEqualTo(6);
		assertThat(count("select count(*) from app.learning_packages "
				+ "where id::text like '" + PREFIX + "%' and status = 'published' and title is not null"))
			.isEqualTo(6);
		assertThat(count("select count(*) from app.quiz_questions q "
				+ "join app.learning_packages p on p.id = q.package_id where p.id::text like '" + PREFIX + "%'"))
			.isEqualTo(30);
		assertThat(count("select count(*) from app.package_content c "
				+ "join app.learning_packages p on p.id = c.package_id where p.id::text like '" + PREFIX + "%' "
				+ "and array_length(regexp_split_to_array(trim(c.summary), '[[:space:]]+'), 1) between 280 and 450 "
				+ "and length(c.summary) - length(replace(c.summary, E'\\n', '')) = 6"))
			.isEqualTo(6);
		assertThat(count("select count(*) from app.learning_packages "
				+ "where id::text like '" + PREFIX + "%' and package_type = 'with_visual'"))
			.isEqualTo(1);
		assertThat(count("select count(*) from app.learning_packages "
				+ "where id::text like '" + PREFIX + "%' and resource_type = 'video'"))
			.isEqualTo(1);

		assertThat(this.jdbc.queryForObject("select resource_link from app.learning_packages "
				+ "where id::text like '" + PREFIX + "%' and resource_type = 'video'", String.class))
			.isEqualTo("https://www.youtube.com/watch?v=AEaKrq3SpW8");

		String path = this.jdbc.queryForObject(
				"select storage_path from app.package_images where package_id = ? and source = 'contributor'",
				String.class, IMAGE_PACKAGE);
		assertThat(this.imageStore.get(path)).isNotEmpty();

		JsonNode items = json(as(DEVICE, get("/api/v1/packages")), 200).get("items");
		assertThat(items).hasSize(6);

		assertThat(this.seeder.seed()).isZero();
		assertThat(count("select count(*) from app.learning_packages where id::text like '" + PREFIX + "%'"))
			.isEqualTo(6);
		assertThat(count("select count(*) from app.quiz_questions q "
				+ "join app.learning_packages p on p.id = q.package_id where p.id::text like '" + PREFIX + "%'"))
			.isEqualTo(30);
	}

	private int count(String sql) {
		Integer count = this.jdbc.queryForObject(sql, Integer.class);
		return count == null ? 0 : count;
	}

}
