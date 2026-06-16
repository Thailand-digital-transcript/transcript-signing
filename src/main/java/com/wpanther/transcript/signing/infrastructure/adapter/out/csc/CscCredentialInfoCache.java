package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoRequest;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoResponse;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class CscCredentialInfoCache {

    private final CscCredentialInfoClient credentialInfoClient;
    private final CscProperties cscProperties;
    private final Map<String, String> certByCredentialId = new ConcurrentHashMap<>();

    /** Existing single-doc API: certificate for the default configured credential. */
    public String getCertificate() {
        return getCertificate(cscProperties.getCredentialId());
    }

    /** Returns (and caches) the leaf certificate for a specific CSC credential. */
    public String getCertificate(String credentialId) {
        return certByCredentialId.computeIfAbsent(credentialId, this::fetch);
    }

    public void invalidate(String credentialId) {
        certByCredentialId.remove(credentialId);
    }

    private String fetch(String credentialId) {
        var request = new CscCredentialInfoRequest();
        request.setCredentialID(credentialId);
        CscCredentialInfoResponse response = credentialInfoClient.getCredentialInfo(request);
        List<String> certs = response.getCert() != null ? response.getCert().getCertificates() : null;
        if (certs == null || certs.isEmpty()) {
            throw new SigningException("CSC_CERT_MISSING",
                    "No certificate returned from CSC credential info for " + credentialId);
        }
        log.info("Cached CSC certificate for credentialId={}", credentialId);
        return certs.get(0);
    }
}
