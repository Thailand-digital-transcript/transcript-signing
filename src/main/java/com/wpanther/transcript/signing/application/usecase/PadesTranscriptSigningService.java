package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.*;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.CscCredentialInfoCache;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PadesTranscriptSigningService {

    private final CscAuthorizationPort cscAuthorizationPort;
    private final CscSignaturePort cscSignaturePort;
    private final PadesEmbeddingPort padesEmbeddingPort;
    private final DocumentStoragePort documentStoragePort;
    private final CscCredentialInfoCache credentialInfoCache;
    private final CscProperties cscProperties;

    public SignHashResult computeAndSign(byte[] pdfBytes) {
        PadesDigestResult digestResult = padesEmbeddingPort.computeByteRangeDigest(pdfBytes);
        // CSC signs SHA-256(DER(signedAttrs)), not the raw byte-range hash
        String sadToken = cscAuthorizationPort.authorize(
                cscProperties.getCredentialId(), digestResult.signedAttrsDigestBase64(), cscProperties.getPin());
        String signatureBase64 = cscSignaturePort.signHash(
                digestResult.signedAttrsDigestBase64(), sadToken,
                cscProperties.getCredentialId(),
                cscProperties.getHashAlgorithmOid());
        String certificate = credentialInfoCache.getCertificate();
        return new SignHashResult(UUID.randomUUID().toString(), signatureBase64, certificate);
    }

    public SigningResult embedAndUpload(byte[] pdfBytes, String pendingSignature,
                                         String certificate, String documentId, int retryCount) {
        PadesDigestResult digestResult = padesEmbeddingPort.computeByteRangeDigest(pdfBytes);
        byte[] signedBytes = padesEmbeddingPort.embedSignature(digestResult, pendingSignature, certificate);
        String key = String.format("PDF/%s/attempt-%d/signed.pdf", documentId, retryCount);
        StorageResult storage = documentStoragePort.upload(signedBytes, key);
        return new SigningResult(storage.path(), storage.url(), storage.size(),
                cscProperties.getPades().getSignatureLevel(), Instant.now());
    }
}
