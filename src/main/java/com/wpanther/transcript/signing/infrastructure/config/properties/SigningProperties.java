package com.wpanther.transcript.signing.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.signing")
@Getter
@Setter
public class SigningProperties {
    private int maxRetries = 3;
    private int timeoutSeconds = 30;
}
