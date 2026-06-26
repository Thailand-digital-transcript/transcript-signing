package com.wpanther.transcript.signing.application;

import com.wpanther.transcript.signing.application.dto.SignHashResult;
import com.wpanther.transcript.signing.application.dto.SigningResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.port.out.*;

import java.util.List;
import com.wpanther.transcript.signing.application.usecase.XadesTranscriptSigningService;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.CscCredentialInfoCache;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XadesTranscriptSigningServiceTest {

    @Mock CscAuthorizationPort cscAuthorizationPort;
    @Mock CscSignaturePort cscSignaturePort;
    @Mock XadesPreparePort xadesPreparePort;
    @Mock DocumentStoragePort documentStoragePort;
    @Mock CscCredentialInfoCache credentialInfoCache;

    CscProperties cscProperties;
    XadesTranscriptSigningService service;

    @BeforeEach
    void setUp() {
        cscProperties = new CscProperties();
        cscProperties.setCredentialId("cred-001");
        cscProperties.setHashAlgorithmOid("2.16.840.1.101.3.4.2.1");
        cscProperties.setPin("1234");
        CscProperties.Xades xades = new CscProperties.Xades();
        xades.setSignatureLevel("XAdES-BASELINE-B");
        cscProperties.setXades(xades);

        service = new XadesTranscriptSigningService(
                cscAuthorizationPort, cscSignaturePort, xadesPreparePort,
                documentStoragePort, credentialInfoCache, cscProperties);
    }

    @Test
    void computeAndSign_sendsSignedInfoDigestToCsc() {
        byte[] xmlBytes = "<transcript/>".getBytes();
        when(credentialInfoCache.getCertificate()).thenReturn("CERTPEM");
        when(xadesPreparePort.prepare(eq(xmlBytes), eq("CERTPEM"), any(Instant.class), anyString()))
                .thenReturn(new XadesPreparation("SI_DIGEST", new byte[]{1}));
        when(cscAuthorizationPort.authorize("cred-001", "SI_DIGEST", "1234")).thenReturn("SAD");
        when(cscSignaturePort.signHash(eq("SI_DIGEST"), eq("SAD"),
                eq("cred-001"), eq("2.16.840.1.101.3.4.2.1")))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-12345", List.of("SIGVAL")));

        SignHashResult result = service.computeAndSign(xmlBytes);

        assertThat(result.pendingSignature()).isEqualTo("SIGVAL");
        assertThat(result.transactionId()).isEqualTo("txn-12345");
        assertThat(result.certificate()).isEqualTo("CERTPEM");
        assertThat(result.sigId()).isNotBlank();
        assertThat(result.signingTime()).isNotNull();
        // The digest CSC sees must be the SignedInfo digest, NOT raw-doc SHA-256
        verify(cscAuthorizationPort).authorize("cred-001", "SI_DIGEST", "1234");
        verify(cscSignaturePort).signHash(eq("SI_DIGEST"), eq("SAD"),
                eq("cred-001"), eq("2.16.840.1.101.3.4.2.1"));
    }

    @Test
    void computeAndSign_signingTimeHasMillisecondPrecision() {
        // Guards the truncatedTo(MILLIS) fix: signingTime is persisted to a TIMESTAMPTZ column
        // (microsecond precision) at the TX1.5 checkpoint and re-fed into embed on resume. If it
        // carried sub-millisecond (nanosecond) precision from Instant.now(), the DB round-trip
        // would silently alter it, producing a different SignedInfo and a non-verifying signature.
        byte[] xmlBytes = "<transcript/>".getBytes();
        when(credentialInfoCache.getCertificate()).thenReturn("CERTPEM");
        when(xadesPreparePort.prepare(eq(xmlBytes), eq("CERTPEM"), any(Instant.class), anyString()))
                .thenReturn(new XadesPreparation("SI_DIGEST", new byte[]{1}));
        when(cscAuthorizationPort.authorize("cred-001", "SI_DIGEST", "1234")).thenReturn("SAD");
        when(cscSignaturePort.signHash(eq("SI_DIGEST"), eq("SAD"),
                eq("cred-001"), eq("2.16.840.1.101.3.4.2.1")))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-123", List.of("SIGVAL")));

        SignHashResult result = service.computeAndSign(xmlBytes);

        assertThat(result.signingTime().getNano() % 1_000_000)
                .as("signingTime must be truncated to millis to survive the TIMESTAMPTZ round-trip")
                .isZero();
    }

    @Test
    void embedAndUpload_usesCheckpointedParams() {
        byte[] xmlBytes = "<transcript/>".getBytes();
        byte[] signedBytes = "<signed/>".getBytes();
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        when(xadesPreparePort.embed(xmlBytes, "CERTPEM", t, "Sig-1", "SIGVAL"))
                .thenReturn(signedBytes);
        when(documentStoragePort.upload(signedBytes, "XML/doc-001/attempt-2/signed.xml"))
                .thenReturn(new StorageResult("XML/doc-001/attempt-2/signed.xml",
                        "http://minio/signed.xml", signedBytes.length));

        SigningResult result = service.embedAndUpload(xmlBytes, "SIGVAL", "CERTPEM",
                "doc-001", 2, "Sig-1", t);

        assertThat(result.signedDocPath()).isEqualTo("XML/doc-001/attempt-2/signed.xml");
        assertThat(result.signedDocUrl()).isEqualTo("http://minio/signed.xml");
        assertThat(result.signedDocSize()).isEqualTo(signedBytes.length);
        assertThat(result.signatureLevel()).isEqualTo("XAdES-BASELINE-B");
        assertThat(result.signatureTimestamp()).isNotNull();
        verify(xadesPreparePort).embed(xmlBytes, "CERTPEM", t, "Sig-1", "SIGVAL");
    }
}
