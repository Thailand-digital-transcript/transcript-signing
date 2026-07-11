package com.wpanther.transcript.signing.domain;

import com.wpanther.transcript.signing.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchSigningJobTest {

    private BatchSigningItem item(String docId, BatchItemStatus status, String sig) {
        return BatchSigningItem.rehydrate(java.util.UUID.randomUUID(), docId, "num-" + docId,
                "XML/" + docId + "/orig.xml", status, "Sig-" + docId,
                Instant.parse("2026-06-16T10:00:00Z"), sig, null, null, null, null);
    }

    @Test
    void itemsNeedingSignature_excludesSignedAndAlreadyCheckpointed() {
        BatchSigningJob job = BatchSigningJob.rehydrate(
                java.util.UUID.randomUUID(), "corr-1", "batch-1", "saga-1", SignerRole.REGISTRAR,
                SigningFormat.XML, BatchJobStatus.SIGNING, List.of(
                        item("d1", BatchItemStatus.SIGNED, "sig1"),         // done
                        item("d2", BatchItemStatus.PENDING, "sig2"),        // checkpointed, needs embed only
                        item("d3", BatchItemStatus.PENDING, null)),         // needs fresh CSC sign
                0L);

        assertThat(job.itemsNeedingFreshSignature()).extracting(BatchSigningItem::getDocumentId)
                .containsExactly("d3");
        assertThat(job.itemsNeedingEmbed()).extracting(BatchSigningItem::getDocumentId)
                .containsExactlyInAnyOrder("d2", "d3");  // d3 after it gets a signature; d2 already has one
    }

    @Test
    void allItemsSigned_trueOnlyWhenEveryItemSigned() {
        BatchSigningJob job = BatchSigningJob.rehydrate(
                java.util.UUID.randomUUID(), "corr-1", "batch-1", "saga-1", SignerRole.DEAN,
                SigningFormat.XML, BatchJobStatus.SIGNING, List.of(
                        item("d1", BatchItemStatus.SIGNED, "s"),
                        item("d2", BatchItemStatus.FAILED, "s")), 0L);
        assertThat(job.allItemsSigned()).isFalse();
    }

    @Test
    void anyItemSigned_trueWhenAtLeastOneIsSigned() {
        BatchSigningJob job = BatchSigningJob.rehydrate(
                java.util.UUID.randomUUID(), "corr-1", "batch-1", "saga-1", SignerRole.DEAN,
                SigningFormat.XML, BatchJobStatus.SIGNING, List.of(
                        item("d1", BatchItemStatus.SIGNED, "s"),
                        item("d2", BatchItemStatus.FAILED, "s")), 0L);
        assertThat(job.anyItemSigned()).isTrue();
    }

    @Test
    void anyItemSigned_falseWhenNoneSigned() {
        BatchSigningJob job = BatchSigningJob.rehydrate(
                java.util.UUID.randomUUID(), "corr-1", "batch-1", "saga-1", SignerRole.REGISTRAR,
                SigningFormat.XML, BatchJobStatus.SIGNING, List.of(
                        item("d1", BatchItemStatus.PENDING, null),
                        item("d2", BatchItemStatus.FAILED, null)), 0L);
        assertThat(job.anyItemSigned()).isFalse();
    }

    @Test
    void finish_marksFailedWhenAnyItemNotSigned() {
        java.util.UUID id = java.util.UUID.randomUUID();
        BatchSigningJob job = BatchSigningJob.rehydrate(
                id, "corr-1", "batch-1", "saga-1", SignerRole.REGISTRAR,
                SigningFormat.XML, BatchJobStatus.SIGNING, List.of(
                        item("d1", BatchItemStatus.SIGNED, "s"),
                        item("d2", BatchItemStatus.FAILED, "s")), 0L);
        job.finish();
        assertThat(job.getStatus()).isEqualTo(BatchJobStatus.FAILED);
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void getters_exposeAllFields() {
        java.util.UUID id = java.util.UUID.randomUUID();
        BatchSigningJob job = BatchSigningJob.rehydrate(
                id, "corr-1", "batch-1", "saga-1", SignerRole.DEAN,
                SigningFormat.PDF, BatchJobStatus.PENDING,
                List.of(item("d1", BatchItemStatus.PENDING, null)), 7L);

        assertThat(job.getId()).isEqualTo(id);
        assertThat(job.getCorrelationId()).isEqualTo("corr-1");
        assertThat(job.getBatchId()).isEqualTo("batch-1");
        assertThat(job.getSagaId()).isEqualTo("saga-1");
        assertThat(job.getSignerRole()).isEqualTo(SignerRole.DEAN);
        assertThat(job.getFormat()).isEqualTo(SigningFormat.PDF);
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getVersion()).isEqualTo(7L);
        job.setVersion(8L);
        assertThat(job.getVersion()).isEqualTo(8L);
    }
}
