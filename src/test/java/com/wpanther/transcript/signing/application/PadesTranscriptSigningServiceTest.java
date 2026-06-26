package com.wpanther.transcript.signing.application;

import com.wpanther.transcript.signing.application.dto.*;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.application.usecase.PadesTranscriptSigningService;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.CscCredentialInfoCache;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PadesTranscriptSigningServiceTest {

    @Mock CscAuthorizationPort cscAuthorizationPort;
    @Mock CscSignaturePort cscSignaturePort;
    @Mock PadesEmbeddingPort padesEmbeddingPort;
    @Mock DocumentStoragePort documentStoragePort;
    @Mock CscCredentialInfoCache credentialInfoCache;

    PadesTranscriptSigningService service;

    @BeforeEach
    void setUp() {
        CscProperties cscProperties = new CscProperties();
        cscProperties.setCredentialId("cred-001");
        cscProperties.setHashAlgorithmOid("2.16.840.1.101.3.4.2.1");
        cscProperties.setPin("");
        CscProperties.Pades pades = new CscProperties.Pades();
        pades.setSignatureLevel("PAdES-B-B");
        cscProperties.setPades(pades);

        service = new PadesTranscriptSigningService(
                cscAuthorizationPort, cscSignaturePort, padesEmbeddingPort,
                documentStoragePort, credentialInfoCache, cscProperties);
    }

    @Test
    void computeAndSign_computesByteRangeDigest_callsCscInOrder() {
        byte[] pdfBytes = "%PDF-1.7\n...".getBytes();
        // signedAttrsDigestBase64 is the hash of DER(signedAttrs) — what CSC receives
        PadesDigestResult digestResult = new PadesDigestResult(
                new byte[]{1, 2, 3}, "signedAttrsDigest==", new byte[]{0x30, 0x00});
        when(padesEmbeddingPort.computeByteRangeDigest(pdfBytes)).thenReturn(digestResult);
        when(cscAuthorizationPort.authorize(eq("cred-001"), eq("signedAttrsDigest=="), eq("")))
                .thenReturn("sad-token-pdf");
        when(cscSignaturePort.signHash(eq("signedAttrsDigest=="), eq("sad-token-pdf"),
                eq("cred-001"), anyString()))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-pdf-123", java.util.List.of("cmsSig==")));
        when(credentialInfoCache.getCertificate()).thenReturn("cert-pem");

        SignHashResult result = service.computeAndSign(pdfBytes);

        assertThat(result.pendingSignature()).isEqualTo("cmsSig==");
        assertThat(result.certificate()).isEqualTo("cert-pem");
        verify(padesEmbeddingPort).computeByteRangeDigest(pdfBytes);
        verify(cscAuthorizationPort).authorize("cred-001", "signedAttrsDigest==", "");
    }

    @Test
    void embedAndUpload_callsEmbedderWithPreparedPdfAndUploadsToCorrectKey() {
        byte[] pdfBytes = "%PDF-1.7\n...".getBytes();
        byte[] signedPdfBytes = "%PDF-1.7\n...signed".getBytes();
        PadesDigestResult digestResult = new PadesDigestResult(
                pdfBytes, "signedAttrsDigest==", new byte[]{0x30, 0x00});
        when(padesEmbeddingPort.computeByteRangeDigest(pdfBytes)).thenReturn(digestResult);
        when(padesEmbeddingPort.embedSignature(digestResult, "sig==", "cert-pem"))
                .thenReturn(signedPdfBytes);
        when(documentStoragePort.upload(signedPdfBytes, "PDF/doc-001/attempt-0/signed.pdf"))
                .thenReturn(new StorageResult("PDF/doc-001/attempt-0/signed.pdf", "http://url", signedPdfBytes.length));

        SigningResult result = service.embedAndUpload(pdfBytes, "sig==", "cert-pem", "doc-001", 0);

        assertThat(result.signedDocPath()).isEqualTo("PDF/doc-001/attempt-0/signed.pdf");
        assertThat(result.signatureLevel()).isEqualTo("PAdES-B-B");
    }
}
