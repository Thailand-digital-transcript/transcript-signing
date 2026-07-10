package com.wpanther.transcript.signing.domain;

import com.wpanther.transcript.signing.domain.model.BatchItemStatus;
import com.wpanther.transcript.signing.domain.model.BatchSigningItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchSigningItemTest {

    @Test
    void create_assignsGeneratedIdAndPendingStatus() {
        BatchSigningItem item = BatchSigningItem.create("doc-1", "num-1", "XML/doc-1/orig.xml");
        assertThat(item.getId()).isNotNull();
        assertThat(item.getDocumentId()).isEqualTo("doc-1");
        assertThat(item.getDocumentNumber()).isEqualTo("num-1");
        assertThat(item.getSourceStorageKey()).isEqualTo("XML/doc-1/orig.xml");
        assertThat(item.getStatus()).isEqualTo(BatchItemStatus.PENDING);
    }

    @Test
    void rehydrate_restoresAllFields() {
        UUID id = UUID.randomUUID();
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        BatchSigningItem item = BatchSigningItem.rehydrate(id, "doc-1", "num-1",
                "XML/doc-1/orig.xml", BatchItemStatus.SIGNED, "Sig-1", t, "sig-base64",
                "XML/doc-1/signed.xml", "http://minio/signed.xml", null, 2048L);
        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDocumentId()).isEqualTo("doc-1");
        assertThat(item.getDocumentNumber()).isEqualTo("num-1");
        assertThat(item.getStatus()).isEqualTo(BatchItemStatus.SIGNED);
        assertThat(item.getSigId()).isEqualTo("Sig-1");
        assertThat(item.getSigningTime()).isEqualTo(t);
        assertThat(item.getPendingSignature()).isEqualTo("sig-base64");
        assertThat(item.getSignedDocKey()).isEqualTo("XML/doc-1/signed.xml");
        assertThat(item.getSignedDocUrl()).isEqualTo("http://minio/signed.xml");
        assertThat(item.getSignedDocSize()).isEqualTo(2048L);
        assertThat(item.getErrorMessage()).isNull();
    }

    @Test
    void markFailed_setsErrorMessageAndStatus() {
        BatchSigningItem item = BatchSigningItem.create("doc-1", "num-1", "XML/doc-1/orig.xml");
        item.markFailed("CSC timeout");
        assertThat(item.getStatus()).isEqualTo(BatchItemStatus.FAILED);
        assertThat(item.getErrorMessage()).isEqualTo("CSC timeout");
    }

    @Test
    void checkpoint_setsSigIdSigningTimeAndPendingSignature() {
        BatchSigningItem item = BatchSigningItem.create("doc-1", "num-1", "XML/doc-1/orig.xml");
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        item.checkpoint("Sig-1", t, "sig-base64");
        assertThat(item.getSigId()).isEqualTo("Sig-1");
        assertThat(item.getSigningTime()).isEqualTo(t);
        assertThat(item.getPendingSignature()).isEqualTo("sig-base64");
        assertThat(item.hasSignature()).isTrue();
    }
}
