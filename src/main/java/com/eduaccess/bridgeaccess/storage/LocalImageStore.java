package com.eduaccess.bridgeaccess.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Development {@link ImageStore}: files under {@code bridgeaccess.storage.local-dir}, used
 * when Supabase Storage is not configured (see {@code StorageConfig}).
 * Writes go to a temporary file first and are then moved into place, so a crash never leaves
 * a half-written image at the final path.
 */
public class LocalImageStore implements ImageStore {

	private final Path root;

	public LocalImageStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	@Override
	public void put(String path, byte[] bytes, String mimeType) {
		Path target = resolve(path);
		try {
			Files.createDirectories(target.getParent());
			Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
			try {
				Files.write(temp, bytes);
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not store image " + path, ex);
		}
	}

	@Override
	public byte[] get(String path) {
		Path file = resolve(path);
		try {
			return Files.readAllBytes(file);
		}
		catch (NoSuchFileException ex) {
			throw new ImageNotFoundException(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read image " + path, ex);
		}
	}

	@Override
	public void delete(String path) {
		try {
			Files.deleteIfExists(resolve(path));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not delete image " + path, ex);
		}
	}

	/** Paths are built by the server, but refuse anything that would escape the root anyway. */
	private Path resolve(String path) {
		Path resolved = this.root.resolve(path).normalize();
		if (!resolved.startsWith(this.root) || resolved.equals(this.root)) {
			throw new IllegalArgumentException("Invalid storage path: " + path);
		}
		return resolved;
	}

}
