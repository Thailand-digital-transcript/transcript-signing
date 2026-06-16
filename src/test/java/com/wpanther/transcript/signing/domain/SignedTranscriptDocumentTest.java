package com.wpanther.transcript.signing.domain;

import com.wpanther.transcript.signing.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class SignedTranscriptDocumentTest {

    @Test
    void startSigning_fromPending_transitionsToSigning() {
        var doc = pendingDoc();
        doc.startSigning();
        assertThat(doc.getStatus()).isEqualTo(SigningStatus.SIGNING);
    }

    @Test
    void startSigning_fromFailed_transitionsToSigning() {
        var doc = pendingDoc();
        doc.markFailed("previous error");
        doc.startSigning();
        assertThat(doc.getStatus()).isEqualTo(SigningStatus.SIGNING);
    }

    @Test
    void startSigning_fromCompleted_throwsSigningException() {
        var doc = pendingDoc();
        doc.startSigning();
        doc.markCompleted("/path", "http://url", 1024L, "XAdES-BASELINE-B", Instant.now());
        assertThatThrownBy(doc::startSigning)
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void startSigning_fromSigning_throwsSigningException() {
        var doc = pendingDoc();
        doc.startSigning();
        assertThatThrownBy(doc::startSigning)
                .isInstanceOf(SigningException.class);
    }

    @Test
    void markCompleted_fromSigning_transitionsToCompleted() {
        var doc = pendingDoc();
        doc.startSigning();
        Instant now = Instant.now();
        doc.markCompleted("/signed/path.xml", "http://url/signed.xml", 2048L, "XAdES-BASELINE-B", now);
        assertThat(doc.getStatus()).isEqualTo(SigningStatus.COMPLETED);
        assertThat(doc.getSignedDocPath()).isEqualTo("/signed/path.xml");
        assertThat(doc.getSignedDocUrl()).isEqualTo("http://url/signed.xml");
        assertThat(doc.getSignedDocSize()).isEqualTo(2048L);
        assertThat(doc.getSignatureLevel()).isEqualTo("XAdES-BASELINE-B");
        assertThat(doc.getSignatureTimestamp()).isEqualTo(now);
        assertThat(doc.getCompletedAt()).isNotNull();
        assertThat(doc.getPendingSignature()).isNull();
    }

    @Test
    void markCompleted_fromPending_throwsSigningException() {
        var doc = pendingDoc();
        assertThatThrownBy(() ->
                doc.markCompleted("/path", "http://url", 100L, "XAdES-BASELINE-B", Instant.now()))
                .isInstanceOf(SigningException.class);
    }

    @Test
    void markFailed_fromAnyState_setsFailedAndIncrementsRetryCount() {
        var doc = pendingDoc();
        doc.markFailed("CSC timeout");
        assertThat(doc.getStatus()).isEqualTo(SigningStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("CSC timeout");
        assertThat(doc.getRetryCount()).isEqualTo(1);
    }

    @Test
    void saveTransactionCheckpoint_persistsTransactionIdAndPendingSignature() {
        var doc = pendingDoc();
        doc.startSigning();
        doc.saveTransactionCheckpoint("txn-123", "base64sig==", "cert-pem", null, null);
        assertThat(doc.getTransactionId()).isEqualTo("txn-123");
        assertThat(doc.getPendingSignature()).isEqualTo("base64sig==");
        assertThat(doc.getCertificate()).isEqualTo("cert-pem");
    }

    @org.junit.jupiter.api.Test
    void saveTransactionCheckpoint_persistsXadesSigningParams() {
        var doc = com.wpanther.transcript.signing.domain.model.SignedTranscriptDocument.create(
                "DOC-1", "NUM-1",
                com.wpanther.transcript.signing.domain.model.SigningFormat.XML,
                "path", "url", 10L);
        doc.startSigning();
        java.time.Instant t = java.time.Instant.parse("2026-06-16T10:00:00Z");

        doc.saveTransactionCheckpoint("tx-1", "sigval", "certpem", "Sig-1", t);

        org.assertj.core.api.Assertions.assertThat(doc.getSigId()).isEqualTo("Sig-1");
        org.assertj.core.api.Assertions.assertThat(doc.getSigningTime()).isEqualTo(t);
    }

    @Test
    void isMaxRetriesExceeded_returnsTrueWhenRetryCountReachesMax() {
        var doc = pendingDoc();
        doc.markFailed("err");
        doc.markFailed("err");
        doc.markFailed("err");
        assertThat(doc.isMaxRetriesExceeded(3)).isTrue();
        assertThat(doc.isMaxRetriesExceeded(4)).isFalse();
    }

    private SignedTranscriptDocument pendingDoc() {
        return SignedTranscriptDocument.create(
                "doc-001", "TH-2026-001", SigningFormat.XML,
                "XML/doc-001/attempt-0/original.xml", "http://minio/original.xml", 1024L);
    }
}
