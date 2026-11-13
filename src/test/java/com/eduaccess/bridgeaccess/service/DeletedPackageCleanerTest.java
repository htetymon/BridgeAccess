package com.eduaccess.bridgeaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.eduaccess.bridgeaccess.repository.MyPackageRepository;
import com.eduaccess.bridgeaccess.storage.ImageStore;

class DeletedPackageCleanerTest {

	private static final UUID A = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID B = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");

	private final MyPackageRepository repository = mock();

	private final ImageStore store = mock();

	private final DeletedPackageCleaner cleaner = new DeletedPackageCleaner(this.repository, this.store);

	@Test
	void removesFilesThenRows() {
		assertThat(this.cleaner.clean(A)).isTrue();

		var order = org.mockito.Mockito.inOrder(this.store, this.repository);
		order.verify(this.store).deleteFolder(A.toString());
		order.verify(this.repository).deleteImageRows(A);
	}

	@Test
	void keepsRowsWhenStorageFails() {
		given(this.store.deleteFolder(A.toString())).willThrow(new RuntimeException("storage down"));

		assertThat(this.cleaner.clean(A)).isFalse();

		verify(this.repository, never()).deleteImageRows(any());
	}

	@Test
	void sweepCleansWhatIsPending() {
		given(this.repository.findDeletedWithImages(DeletedPackageCleaner.GRACE, DeletedPackageCleaner.BATCH))
			.willReturn(List.of(A, B));

		this.cleaner.sweep();

		verify(this.repository).deleteImageRows(A);
		verify(this.repository).deleteImageRows(B);
	}

	@Test
	void sweepStopsAtTheFirstStorageFailure() {
		given(this.repository.findDeletedWithImages(any(), anyInt())).willReturn(List.of(A, B));
		given(this.store.deleteFolder(A.toString())).willThrow(new RuntimeException("storage down"));

		this.cleaner.sweep();

		verify(this.store, never()).deleteFolder(B.toString());
	}

	@Test
	void sweepSurvivesADatabaseOutage() {
		given(this.repository.findDeletedWithImages(any(), anyInt()))
			.willThrow(new DataAccessResourceFailureException("down"));

		this.cleaner.sweep();

		verifyNoInteractions(this.store);
	}

}
