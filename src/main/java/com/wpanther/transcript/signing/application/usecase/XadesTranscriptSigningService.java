package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.SignHashResult;
import com.wpanther.transcript.signing.application.dto.SigningResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.CscCredentialInfoCache;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class XadesTranscriptSigningService {

    private final CscAuthorizationPort cscAuthorizationPort;
    private final CscSignaturePort cscSignaturePort;
    private final XadesEmbeddingPort xadesEmbeddingPort;
    private final DocumentStoragePort documentStoragePort;
    private final CscCredentialInfoCache credentialInfoCache;
    private final CscProperties cscProperties;

    public SignHashResult computeAndSign(byte[] xmlBytes) {
        String hashBase64 = sha256Base64(xmlBytes);
        String sadToken = cscAuthorizationPort.authorize(
                cscProperties.getCredentialId(), hashBase64, cscProperties.getPin());
        String signatureBase64 = cscSignaturePort.signHash(
                hashBase64, sadToken,
                cscProperties.getCredentialId(),
                cscProperties.getHashAlgorithmOid());
        String certificate = credentialInfoCache.getCertificate();
        return new SignHashResult(UUID.randomUUID().toString(), signatureBase64, certificate);
    }

    public SigningResult embedAndUpload(byte[] xmlBytes, String pendingSignature,
                                         String certificate, String documentId, int retryCount) {
        byte[] signedBytes = xadesEmbeddingPort.embedSignature(xmlBytes, pendingSignature, certificate);
        String key = String.format("XML/%s/attempt-%d/signed.xml", documentId, retryCount);
        StorageResult storage = documentStoragePort.upload(signedBytes, key);
        return new SigningResult(storage.path(), storage.url(), storage.size(),
                cscProperties.getXades().getSignatureLevel(), Instant.now());
    }

    private String sha256Base64(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
