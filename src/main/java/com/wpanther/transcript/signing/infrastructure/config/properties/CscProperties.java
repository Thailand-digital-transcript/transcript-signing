package com.wpanther.transcript.signing.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}
