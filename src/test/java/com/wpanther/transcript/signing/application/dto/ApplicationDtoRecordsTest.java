package com.wpanther.transcript.signing.application.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationDtoRecordsTest {

    @Test
    void signHashResult_recordsAllFields() {
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        SignHashResult r = new SignHashResult("txn-1", "sig-base64", "cert-pem", "Sig-1", t);
        assertThat(r.transactionId()).isEqualTo("txn-1");
        assertThat(r.pendingSignature()).isEqualTo("sig-base64");
        assertThat(r.certificate()).isEqualTo("cert-pem");
        assertThat(r.sigId()).isEqualTo("Sig-1");
        assertThat(r.signingTime()).isEqualTo(t);
    }

    @Test
    void signingResult_recordsAllFields() {
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        SigningResult r = new SigningResult("XML/doc-1/signed.xml", "http://minio/signed.xml",
                2048L, "XAdES-BASELINE-B", t);
        assertThat(r.signedDocPath()).isEqualTo("XML/doc-1/signed.xml");
        assertThat(r.signedDocUrl()).isEqualTo("http://minio/signed.xml");
        assertThat(r.signedDocSize()).isEqualTo(2048L);
        assertThat(r.signatureLevel()).isEqualTo("XAdES-BASELINE-B");
        assertThat(r.signatureTimestamp()).isEqualTo(t);
    }
}
