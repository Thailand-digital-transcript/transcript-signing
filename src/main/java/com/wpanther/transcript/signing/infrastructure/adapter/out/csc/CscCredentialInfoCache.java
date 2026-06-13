package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import org.springframework.stereotype.Component;

/**
 * Minimal stub. Will be replaced with the real credential info cache implementation in a later task.
 */
@Component
public class CscCredentialInfoCache {

    private String certificate;

    public String getCertificate() {
        return certificate;
    }

    public void setCertificate(String certificate) {
        this.certificate = certificate;
    }
}
