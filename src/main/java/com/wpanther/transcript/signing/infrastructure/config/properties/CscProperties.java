package com.wpanther.transcript.signing.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;

/**
 * Minimal stub. Task 11 will replace this with the full @ConfigurationProperties-annotated version.
 */
@Getter
@Setter
public class CscProperties {

    private String credentialId;
    private String hashAlgorithmOid;
    private String pin;
    private Xades xades = new Xades();
    private Pades pades = new Pades();

    @Getter
    @Setter
    public static class Xades {
        private String signatureLevel;
    }

    @Getter
    @Setter
    public static class Pades {
        private String signatureLevel;
    }
}
