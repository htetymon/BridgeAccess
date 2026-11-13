package com.eduaccess.bridgeaccess.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalImageStoreTest {

	@TempDir
	Path root;

	@Test
	void putsAndDeletes() throws Exception {
		LocalImageStore store = new LocalImageStore(this.root);
		store.put("packages/p1/original-1.png", new byte[] { 1, 2, 3 }, "image/png");

		Path file = this.root.resolve("packages/p1/original-1.png");
		assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
		try (var leftovers = Files.list(file.getParent())) {
			assertThat(leftovers).containsExactly(file); // no temp files left behind
		}

		store.delete("packages/p1/original-1.png");
		assertThat(file).doesNotExist();
		store.delete("packages/p1/original-1.png"); // missing file is fine
	}

	@Test
	void refusesPathsOutsideRoot() {
		LocalImageStore store = new LocalImageStore(this.root.resolve("images"));
		assertThatThrownBy(() -> store.put("../escape.png", new byte[] { 1 }, "image/png"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.delete("")).isInstanceOf(IllegalArgumentException.class);
	}

}
