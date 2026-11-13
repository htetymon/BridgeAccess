package com.eduaccess.bridgeaccess.storage;

/**
 * Where image files live. {@code package_images.storage_path} holds the path given to
 * {@link #put}. The contract names a private Supabase Storage bucket; until that is wired up,
 * {@link LocalImageStore} keeps files on the server's disk.
 */
public interface ImageStore {

	/** Stores the file at {@code path} (relative, '/'-separated), replacing any existing file. */
	void put(String path, byte[] bytes, String mimeType);

	/** Deletes the file if it exists. Never throws for a missing file. */
	void delete(String path);

}
