package com.wpanther.transcript.signing.infrastructure.config.properties;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.wpanther.transcript.signing.domain.model.SignerRole;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "app.csc")
@Getter
@Setter
public class CscProperties {

    private String serviceUrl;
    private String credentialId;
    private String hashAlgorithmOid;
    private String pin;
    private Oauth2 oauth2 = new Oauth2();
    private Xades xades = new Xades();
    private Pades pades = new Pades();
    private Map<SignerRole, Credential> credentials = new EnumMap<>(SignerRole.class);

    public Credential credentialForRole(SignerRole role) {
        Credential c = credentials.get(role);
        if (c == null || c.getCredentialId() == null || c.getCredentialId().isBlank()) {
            throw new IllegalStateException("No CSC credential configured for signer role: " + role);
        }
        return c;
    }

    @Getter @Setter
    public static class Oauth2 {
        private String clientId;
        private String clientSecret;
        private String tokenUrl;
    }

    @Getter @Setter
    public static class Xades {
        private String signatureLevel = "XAdES-BASELINE-B";
    }

    @Getter @Setter
    public static class Pades {
        private String signatureLevel = "PAdES-B-B";
    }

    @Getter @Setter
    public static class Credential {
        private String credentialId;
        private String pin;
    }
}
