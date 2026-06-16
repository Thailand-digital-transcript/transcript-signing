package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.SignHashResult;
import com.wpanther.transcript.signing.application.dto.SigningResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.CscCredentialInfoCache;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class XadesTranscriptSigningService {

    private final CscAuthorizationPort cscAuthorizationPort;
    private final CscSignaturePort cscSignaturePort;
    private final XadesPreparePort xadesPreparePort;
    private final DocumentStoragePort documentStoragePort;
    private final CscCredentialInfoCache credentialInfoCache;
    private final CscProperties cscProperties;

    public SignHashResult computeAndSign(byte[] xmlBytes) {
        String certificate = credentialInfoCache.getCertificate();
        String sigId = "Sig-" + UUID.randomUUID();
        // Truncate to milliseconds: the signingTime is embedded in the xades:SigningTime
        // element and signed as part of C14N(SignedInfo). The same value is persisted at the
        // TX1.5 checkpoint and MUST round-trip through PostgreSQL TIMESTAMPTZ (microsecond
        // precision) byte-for-byte so embed on resume reproduces the exact signed bytes.
        // Instant.now() carries nanoseconds, which would silently truncate on persist and
        // invalidate the signature on resume.
        Instant signingTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        XadesPreparation prep = xadesPreparePort.prepare(xmlBytes, certificate, signingTime, sigId);
        String sadToken = cscAuthorizationPort.authorize(
                cscProperties.getCredentialId(), prep.signedInfoDigestBase64(), cscProperties.getPin());
        String signatureBase64 = cscSignaturePort.signHash(
                prep.signedInfoDigestBase64(), sadToken,
                cscProperties.getCredentialId(),
                cscProperties.getHashAlgorithmOid());

        return new SignHashResult(UUID.randomUUID().toString(), signatureBase64, certificate,
                sigId, signingTime);
    }

    public SigningResult embedAndUpload(byte[] xmlBytes, String pendingSignature,
                                         String certificate, String documentId, int retryCount,
                                         String sigId, Instant signingTime) {
        byte[] signedBytes = xadesPreparePort.embed(
                xmlBytes, certificate, signingTime, sigId, pendingSignature);
        String key = String.format("XML/%s/attempt-%d/signed.xml", documentId, retryCount);
        StorageResult storage = documentStoragePort.upload(signedBytes, key);
        return new SigningResult(storage.path(), storage.url(), storage.size(),
                cscProperties.getXades().getSignatureLevel(), Instant.now());
    }
}
