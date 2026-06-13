package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.application.port.out.CscAuthorizationPort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscAuthorizeRequest;
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
public class CscAuthorizationAdapter implements CscAuthorizationPort {

    private final CscAuthorizationClient authorizationClient;

    @Override
    @CircuitBreaker(name = "csc-authorization")
    @Retry(name = "csc-authorization")
    public String authorize(String credentialId, String hashBase64, String pin) {
        var request = new CscAuthorizeRequest();
        request.setCredentialID(credentialId);
        request.setHash(List.of(hashBase64));
        if (pin != null && !pin.isBlank()) {
            request.setAuthData(List.of(CscAuthorizeRequest.AuthData.pin(pin)));
        }
        try {
            var response = authorizationClient.authorize(request);
            if (response.getSad() == null || response.getSad().isBlank()) {
                throw new SigningException("CSC_AUTH_EMPTY_SAD", "CSC authorize returned empty SAD token");
            }
            return response.getSad();
        } catch (FeignException e) {
            log.error("CSC authorize failed: status={}, body={}", e.status(), e.contentUTF8(), e);
            throw new SigningException("CSC_AUTH_FAILED", "CSC authorization failed: " + e.getMessage(), e);
        }
    }
}
