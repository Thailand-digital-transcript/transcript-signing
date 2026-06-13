package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoRequest;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoResponse;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CscCredentialInfoCache {

    private final CscCredentialInfoClient credentialInfoClient;
    private final CscProperties cscProperties;

    private volatile String certificate;

    @PostConstruct
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("Failed to pre-warm CSC credential cache — will retry on first use", e);
        }
    }

    public String getCertificate() {
        if (certificate == null) {
            synchronized (this) {
                if (certificate == null) {
                    refresh();
                }
            }
        }
        return certificate;
    }

    public void invalidate() {
        certificate = null;
    }

    private void refresh() {
        var request = new CscCredentialInfoRequest();
        request.setCredentialID(cscProperties.getCredentialId());
        CscCredentialInfoResponse response = credentialInfoClient.getCredentialInfo(request);
        List<String> certs = response.getCert() != null ? response.getCert().getCertificates() : null;
        if (certs == null || certs.isEmpty()) {
            throw new SigningException("CSC_CERT_MISSING", "No certificate returned from CSC credential info");
        }
        certificate = certs.get(0);
        log.info("CSC credential info cached, certificate length={}", certificate.length());
    }
}
