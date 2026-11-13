package com.eduaccess.bridgeaccess.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import com.eduaccess.bridgeaccess.config.ImageConfig.ImageProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.StoredImage;
import com.eduaccess.bridgeaccess.service.ImageType;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.KeepOriginal;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Optimised;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Rejected;
import com.eduaccess.bridgeaccess.service.generation.PackageGenerator.Claim;
import com.eduaccess.bridgeaccess.storage.ImageStore;
import com.eduaccess.bridgeaccess.storage.ImageStore.ImageNotFoundException;
import com.eduaccess.bridgeaccess.storage.LocalImageStore;

class PackageImageProcessorTest {

	private static final UUID PACKAGE = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID IMAGE = UUID.fromString("8e2b7a64-1c50-4f3d-9d6a-0b7e5c3a2f19");

	private static final Claim CLAIM = new Claim(PACKAGE, Instant.parse("2026-11-13T15:30:00Z"));

	private static final String ORIGINAL = PACKAGE + "/original-1.png";

	private static final String TARGET = PACKAGE + "/" + IMAGE + ".webp";

	private static final StoredImage ROW = new StoredImage(IMAGE, "contributor", 1, ORIGINAL, "image/png", 400_000);

	private final GenerationRepository repository = mock();

	private final ImageStore store = mock();

	private final ImageOptimizer optimizer = mock();

	private final PackageImageProcessor processor = new PackageImageProcessor(this.repository, this.store,
			this.optimizer);

	private final byte[] png = { 1, 2, 3 };

	private final byte[] webp = { 4, 5 };

	{
		given(this.optimizer.outputType()).willReturn(ImageType.WEBP);
		given(this.repository.findImages(PACKAGE)).willReturn(List.of(ROW));
		given(this.store.get(ORIGINAL)).willReturn(this.png);
	}

	@Test
	void uploadsRecordsThenDeletesTheOriginal() {
		given(this.optimizer.optimize(this.png)).willReturn(new Optimised(this.webp, ImageType.WEBP, 1280, 853));
		given(this.repository.replaceImage(PACKAGE, CLAIM.claimedAt(), IMAGE, TARGET, "image/webp", 2)).willReturn(true);

		this.processor.process(CLAIM);

		var order = org.mockito.Mockito.inOrder(this.store, this.repository);
		order.verify(this.store).put(TARGET, this.webp, "image/webp");
		order.verify(this.repository).replaceImage(PACKAGE, CLAIM.claimedAt(), IMAGE, TARGET, "image/webp", 2);
		order.verify(this.store).delete(ORIGINAL);
	}

	@Test
	void skipsImagesAlreadyOptimised() {
		given(this.repository.findImages(PACKAGE))
			.willReturn(List.of(new StoredImage(IMAGE, "contributor", 1, TARGET, "image/webp", 60_000)));

		this.processor.process(CLAIM);

		verify(this.store, never()).get(anyString());
		verify(this.optimizer, never()).optimize(any());
	}

	@Test
	void keepsTheOriginalWhenItCannotBeReencoded() {
		given(this.optimizer.optimize(this.png)).willReturn(new KeepOriginal("no decoder"));

		this.processor.process(CLAIM);

		verify(this.store, never()).put(anyString(), any(), anyString());
		verify(this.store, never()).delete(anyString());
		verify(this.repository, never()).deleteImage(any(), any(), any());
	}

	@Test
	void removesRejectedImages() {
		given(this.optimizer.optimize(this.png)).willReturn(new Rejected("too many pixels"));
		given(this.repository.deleteImage(PACKAGE, CLAIM.claimedAt(), IMAGE)).willReturn(true);

		this.processor.process(CLAIM);

		verify(this.repository).deleteImage(PACKAGE, CLAIM.claimedAt(), IMAGE);
		verify(this.store).delete(ORIGINAL);
	}

	@Test
	void removesImagesWhoseFileIsGone() {
		given(this.store.get(ORIGINAL)).willThrow(new ImageNotFoundException(ORIGINAL));
		given(this.repository.deleteImage(PACKAGE, CLAIM.claimedAt(), IMAGE)).willReturn(true);

		this.processor.process(CLAIM);

		verify(this.repository).deleteImage(PACKAGE, CLAIM.claimedAt(), IMAGE);
	}

	@Test
	void lostClaimCleansUpTheNewFileAndStops() {
		given(this.optimizer.optimize(this.png)).willReturn(new Optimised(this.webp, ImageType.WEBP, 10, 10));
		given(this.repository.replaceImage(any(), any(), any(), anyString(), anyString(), anyInt())).willReturn(false);

		assertThatThrownBy(() -> this.processor.process(CLAIM)).isInstanceOf(IllegalStateException.class);

		verify(this.store).delete(TARGET);
		verify(this.store, never()).delete(ORIGINAL);
	}

	@Test
	void failingToDeleteTheOriginalDoesNotFailTheStep() {
		given(this.optimizer.optimize(this.png)).willReturn(new Optimised(this.webp, ImageType.WEBP, 10, 10));
		given(this.repository.replaceImage(any(), any(), any(), anyString(), anyString(), anyInt())).willReturn(true);
		org.mockito.BDDMockito.willThrow(new RuntimeException("storage down")).given(this.store).delete(ORIGINAL);

		this.processor.process(CLAIM);
	}

	@Test
	void packageWithoutImagesDoesNothing() {
		given(this.repository.findImages(PACKAGE)).willReturn(List.of());

		this.processor.process(CLAIM);

		verifyNoInteractions(this.store);
	}

	@Test
	void realOptimiserAndDiskStoreEndToEnd(@TempDir Path root) throws Exception {
		LocalImageStore disk = new LocalImageStore(root);
		disk.put(ORIGINAL, com.eduaccess.bridgeaccess.service.image.ImageOptimizerTestSupport.photoPng(2400, 1600),
				"image/png");
		GenerationRepository repo = mock();
		given(repo.findImages(PACKAGE)).willReturn(List.of(ROW));
		given(repo.replaceImage(eq(PACKAGE), any(), eq(IMAGE), eq(TARGET), eq("image/webp"), anyInt())).willReturn(true);
		ImageOptimizer real = new ImageOptimizer(new ImageProperties(DataSize.ofKilobytes(80), 1280, 320, 40_000_000));

		new PackageImageProcessor(repo, disk, real).process(CLAIM);

		byte[] stored = disk.get(TARGET);
		assertThat(stored.length).isLessThanOrEqualTo(80 * 1024);
		assertThat(ImageType.detect(stored)).contains(ImageType.WEBP);
		assertThat(root.resolve(ORIGINAL)).doesNotExist();
		verify(repo).replaceImage(PACKAGE, CLAIM.claimedAt(), IMAGE, TARGET, "image/webp", stored.length);
	}

}
