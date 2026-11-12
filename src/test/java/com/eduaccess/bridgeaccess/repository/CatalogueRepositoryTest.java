package com.eduaccess.bridgeaccess.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogueRepositoryTest {

	@Test
	void likeWildcardsAreEscaped() {
		assertThat(CatalogueRepository.escapeLike("50%_off\\now")).isEqualTo("50\\%\\_off\\\\now");
	}

	@Test
	void stepsFollowPackageType() {
		assertThat(CatalogueRepository.stepsFor("text_only")).containsExactly("summary", "key_concepts", "quiz");
		assertThat(CatalogueRepository.stepsFor("with_visual")).containsExactly("summary", "key_concepts", "diagram",
				"quiz");
	}

}
