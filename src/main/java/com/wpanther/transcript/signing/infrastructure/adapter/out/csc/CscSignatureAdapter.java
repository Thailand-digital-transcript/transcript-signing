package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.application.dto.CscSignatureResult;
import com.wpanther.transcript.signing.application.port.out.CscSignaturePort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscSignHashRequest;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CscSignatureAdapter implements CscSignaturePort {

    private final CscSignHashClient signHashClient;

    @Override
    @CircuitBreaker(name = "csc-signature")
    @Retry(name = "csc-signature")
    public CscSignatureResult signHash(String hashBase64, String sadToken, String credentialId,
                            String hashAlgorithmOid) {
        var request = new CscSignHashRequest();
        request.setCredentialID(credentialId);
        request.setSAD(sadToken);
        request.setHash(List.of(hashBase64));
        request.setHashAlgorithmOID(hashAlgorithmOid);
        // sha256WithRSAEncryption (PKCS#1 v1.5); override if credential uses RSAPSS or ECDSA
        request.setSignAlgo("1.2.840.113549.1.1.11");
        try {
            var response = signHashClient.signHash(request);
            if (response.getSignatures() == null || response.getSignatures().isEmpty()) {
                throw new SigningException("CSC_SIGN_EMPTY", "CSC signHash returned no signatures");
            }
            String transactionId = response.getResponseID();
            if (transactionId == null || transactionId.isBlank()) {
                log.warn("CSC signHash returned blank/missing responseID; using fallback UUID");
                transactionId = java.util.UUID.randomUUID().toString();
            }
            return new CscSignatureResult(transactionId, response.getSignatures());
        } catch (FeignException e) {
            log.error("CSC signHash failed: status={}", e.status(), e);
            throw new SigningException("CSC_SIGN_FAILED", "CSC signHash failed: " + e.getMessage(), e);
        }
    }

    @Override
    @CircuitBreaker(name = "csc-signature")
    @Retry(name = "csc-signature")
    public CscSignatureResult signHash(List<String> hashesBase64, String sadToken, String credentialId,
                                 String hashAlgorithmOid) {
        var request = new CscSignHashRequest();
        request.setCredentialID(credentialId);
        request.setSAD(sadToken);
        request.setHash(hashesBase64);
        request.setHashAlgorithmOID(hashAlgorithmOid);
        request.setSignAlgo("1.2.840.113549.1.1.11"); // sha256WithRSAEncryption (PKCS#1 v1.5)
        try {
            var response = signHashClient.signHash(request);
            if (response.getSignatures() == null
                    || response.getSignatures().size() != hashesBase64.size()) {
                throw new SigningException("CSC_SIGN_COUNT_MISMATCH",
                        "CSC signHash returned " + (response.getSignatures() == null ? 0
                                : response.getSignatures().size()) + " signatures for "
                                + hashesBase64.size() + " hashes");
            }
            String transactionId = response.getResponseID();
            if (transactionId == null || transactionId.isBlank()) {
                log.warn("CSC signHash returned blank/missing responseID; using fallback UUID");
                transactionId = java.util.UUID.randomUUID().toString();
            }
            return new CscSignatureResult(transactionId, response.getSignatures());
        } catch (FeignException e) {
            log.error("CSC signHash failed: status={}", e.status(), e);
            throw new SigningException("CSC_SIGN_FAILED", "CSC signHash failed: " + e.getMessage(), e);
        }
    }
}
