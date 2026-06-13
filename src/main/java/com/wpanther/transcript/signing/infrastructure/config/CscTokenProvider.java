package com.wpanther.transcript.signing.infrastructure.config;

import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CscTokenProvider {

    private final CscProperties cscProperties;
    private final RestTemplate restTemplate;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public String getAccessToken() {
        if (Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return cachedToken;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        if (Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return cachedToken;
        }
        var params = Map.of(
                "grant_type",    "client_credentials",
                "client_id",     cscProperties.getOauth2().getClientId(),
                "client_secret", cscProperties.getOauth2().getClientSecret());
        @SuppressWarnings("unchecked")
        var response = restTemplate.postForObject(
                cscProperties.getOauth2().getTokenUrl(), params, Map.class);
        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("Failed to retrieve CSC access token");
        }
        cachedToken = (String) response.get("access_token");
        int expiresIn = (Integer) response.getOrDefault("expires_in", 3600);
        tokenExpiry = Instant.now().plusSeconds(expiresIn);
        log.debug("CSC OAuth2 token refreshed, expires in {}s", expiresIn);
        return cachedToken;
    }
}
