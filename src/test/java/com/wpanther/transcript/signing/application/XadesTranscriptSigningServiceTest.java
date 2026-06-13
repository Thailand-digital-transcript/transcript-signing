package com.wpanther.transcript.signing.application;

import com.wpanther.transcript.signing.application.dto.SignHashResult;
import com.wpanther.transcript.signing.application.dto.SigningResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.application.usecase.XadesTranscriptSigningService;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.CscCredentialInfoCache;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XadesTranscriptSigningServiceTest {

    @Mock CscAuthorizationPort cscAuthorizationPort;
    @Mock CscSignaturePort cscSignaturePort;
    @Mock XadesEmbeddingPort xadesEmbeddingPort;
    @Mock DocumentStoragePort documentStoragePort;
    @Mock CscCredentialInfoCache credentialInfoCache;

    XadesTranscriptSigningService service;

    @BeforeEach
    void setUp() {
        CscProperties cscProperties = new CscProperties();
        cscProperties.setCredentialId("cred-001");
        cscProperties.setHashAlgorithmOid("2.16.840.1.101.3.4.2.1");
        cscProperties.setPin("1234");
        CscProperties.Xades xades = new CscProperties.Xades();
        xades.setSignatureLevel("XAdES-BASELINE-B");
        cscProperties.setXades(xades);

        service = new XadesTranscriptSigningService(
                cscAuthorizationPort, cscSignaturePort, xadesEmbeddingPort,
                documentStoragePort, credentialInfoCache, cscProperties);
    }

    @Test
    void computeAndSign_computesSha256Digest_callsCscInOrder() {
        byte[] xmlBytes = "<transcript/>".getBytes(StandardCharsets.UTF_8);
        when(cscAuthorizationPort.authorize(eq("cred-001"), anyString(), eq("1234")))
                .thenReturn("sad-token-xyz");
        when(cscSignaturePort.signHash(anyString(), eq("sad-token-xyz"),
                eq("cred-001"), eq("2.16.840.1.101.3.4.2.1")))
                .thenReturn("base64signature==");
        when(credentialInfoCache.getCertificate()).thenReturn("-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----");

        SignHashResult result = service.computeAndSign(xmlBytes);

        assertThat(result.transactionId()).isNotBlank();
        assertThat(result.pendingSignature()).isEqualTo("base64signature==");
        assertThat(result.certificate()).contains("BEGIN CERTIFICATE");
        verify(cscAuthorizationPort).authorize(eq("cred-001"), anyString(), eq("1234"));
        verify(cscSignaturePort).signHash(anyString(), eq("sad-token-xyz"),
                eq("cred-001"), eq("2.16.840.1.101.3.4.2.1"));
    }

    @Test
    void embedAndUpload_callsEmbedderAndUploadsToCorrectKey() {
        byte[] xmlBytes = "<transcript/>".getBytes(StandardCharsets.UTF_8);
        byte[] signedBytes = "<signed/>".getBytes(StandardCharsets.UTF_8);
        when(xadesEmbeddingPort.embedSignature(xmlBytes, "base64sig==", "cert-pem"))
                .thenReturn(signedBytes);
        when(documentStoragePort.upload(signedBytes, "XML/doc-001/attempt-2/signed.xml"))
                .thenReturn(new StorageResult("XML/doc-001/attempt-2/signed.xml", "http://minio/signed.xml", signedBytes.length));

        SigningResult result = service.embedAndUpload(xmlBytes, "base64sig==", "cert-pem", "doc-001", 2);

        assertThat(result.signedDocPath()).isEqualTo("XML/doc-001/attempt-2/signed.xml");
        assertThat(result.signedDocUrl()).isEqualTo("http://minio/signed.xml");
        assertThat(result.signedDocSize()).isEqualTo(signedBytes.length);
        assertThat(result.signatureLevel()).isEqualTo("XAdES-BASELINE-B");
        assertThat(result.signatureTimestamp()).isNotNull();
    }
}
