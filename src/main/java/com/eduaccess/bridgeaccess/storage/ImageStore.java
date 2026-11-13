package com.eduaccess.bridgeaccess.storage;

/**
 * Where image files live. {@code package_images.storage_path} holds the path given to
 * {@link #put}. In production this is the private Supabase Storage bucket
 * ({@link SupabaseImageStore}); without Supabase settings, {@link LocalImageStore} keeps
 * files on the server's disk (see {@code StorageConfig}).
 *
 * <p>
 * Paths are relative and '/'-separated: {@code {packageId}/original-{position}.{ext}} for an
 * upload as received, {@code {packageId}/{imageId}.webp} once optimised.
 */
public interface ImageStore {

	/** Stores the file at {@code path}, replacing any existing file. */
	void put(String path, byte[] bytes, String mimeType);

	/**
	 * Reads a whole file. Images are capped at 500 KB, so a byte array is fine.
	 * @throws ImageNotFoundException if there is no file at {@code path}
	 */
	byte[] get(String path);

	/** Deletes the file if it exists. Never throws for a missing file. */
	void delete(String path);

	/**
	 * Deletes every file directly under {@code folder} (for example a package's
	 * {@code {packageId}} folder), including files no database row points at. Never throws for
	 * a missing or empty folder.
	 *
	 * @return how many files were deleted
	 */
	int deleteFolder(String folder);

	class ImageNotFoundException extends RuntimeException {

		public ImageNotFoundException(String path) {
			super("No image at " + path);
		}

	}

}
