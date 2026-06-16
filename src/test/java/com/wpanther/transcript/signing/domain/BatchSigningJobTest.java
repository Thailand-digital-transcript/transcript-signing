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
}
