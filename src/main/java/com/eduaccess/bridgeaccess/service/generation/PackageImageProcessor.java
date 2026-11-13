package com.eduaccess.bridgeaccess.service.generation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.StoredImage;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.KeepOriginal;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Optimised;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Rejected;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Result;
import com.eduaccess.bridgeaccess.storage.ImageStore;
import com.eduaccess.bridgeaccess.storage.ImageStore.ImageNotFoundException;

/**
 * The image step of the AI job (FR-6.5): each image of a claimed package is resized and
 * re-encoded to about 80 KB, stored at {@code {packageId}/{imageId}.webp} (or {@code .jpg}
 * where WebP is unavailable), and its {@code package_images} row updated.
 *
 * <p>
 * Order per image: upload the new file, then update the row (only while the claim is
 * current), then delete the original. A crash in between leaves at worst an unused file,
 * never a row pointing at a missing one. Running it again is safe: an image already at its
 * optimised path is skipped.
 *
 * <p>
 * An image problem never fails the package: an image that cannot be re-encoded is kept as
 * uploaded (it passed the type and size checks), and one that must not be published (for
 * example a decompression bomb, or its file is gone) is removed from the package. Storage
 * or database being unreachable is different: that propagates and fails the run, like any
 * other infrastructure error.
 */
@Component
public class PackageImageProcessor {

	private static final Logger log = LoggerFactory.getLogger(PackageImageProcessor.class);

	private final GenerationRepository repository;

	private final ImageStore imageStore;

	private final ImageOptimizer optimizer;

	public PackageImageProcessor(GenerationRepository repository, ImageStore imageStore, ImageOptimizer optimizer) {
		this.repository = repository;
		this.imageStore = imageStore;
		this.optimizer = optimizer;
	}

	public void process(PackageGenerator.Claim claim) {
		List<StoredImage> images = this.repository.findImages(claim.packageId());
		for (StoredImage image : images) {
			process(claim, image);
		}
	}

	private void process(PackageGenerator.Claim claim, StoredImage image) {
		String target = targetPath(claim, image);
		if (image.storagePath().equals(target)) {
			return; // optimised by an earlier run
		}
		byte[] original;
		try {
			original = this.imageStore.get(image.storagePath());
		}
		catch (ImageNotFoundException ex) {
			log.warn("Image {} of package {} has no file at {}; removing it", image.id(), claim.packageId(),
					image.storagePath());
			remove(claim, image);
			return;
		}

		Result result = this.optimizer.optimize(original);
		switch (result) {
			case Optimised optimised -> store(claim, image, target, optimised);
			case KeepOriginal keep -> log.warn("Image {} of package {} kept as uploaded ({} bytes): {}", image.id(),
					claim.packageId(), image.sizeBytes(), keep.reason());
			case Rejected rejected -> {
				log.warn("Image {} of package {} rejected: {}", image.id(), claim.packageId(), rejected.reason());
				remove(claim, image);
			}
		}
	}

	private void store(PackageGenerator.Claim claim, StoredImage image, String path, Optimised optimised) {
		this.imageStore.put(path, optimised.bytes(), optimised.type().mimeType());
		if (!this.repository.replaceImage(claim.packageId(), claim.claimedAt(), image.id(), path,
				optimised.type().mimeType(), optimised.bytes().length)) {
			deleteQuietly(path);
			throw new IllegalStateException("Package " + claim.packageId() + " changed while optimising images");
		}
		log.info("Image {} of package {}: {} bytes {} -> {}", image.id(), claim.packageId(), image.sizeBytes(),
				image.mimeType(), optimised);
		if (!path.equals(image.storagePath())) {
			deleteQuietly(image.storagePath());
		}
	}

	private void remove(PackageGenerator.Claim claim, StoredImage image) {
		if (!this.repository.deleteImage(claim.packageId(), claim.claimedAt(), image.id())) {
			throw new IllegalStateException("Package " + claim.packageId() + " changed while optimising images");
		}
		deleteQuietly(image.storagePath());
	}

	/** {@code {packageId}/{imageId}.{ext}} in the format this server writes. */
	private String targetPath(PackageGenerator.Claim claim, StoredImage image) {
		return claim.packageId() + "/" + image.id() + "." + this.optimizer.outputType().extension();
	}

	private void deleteQuietly(String path) {
		try {
			this.imageStore.delete(path);
		}
		catch (RuntimeException ex) {
			log.warn("Could not delete image file {}", path, ex);
		}
	}

}
