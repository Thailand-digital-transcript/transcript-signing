package com.wpanther.transcript.signing.infrastructure.config;

import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
        // OAuth2 client_credentials: the token endpoint requires a
        // form-urlencoded body, and this CSC authenticates the client via HTTP
        // Basic (client_secret_basic). Posting a plain Map sends JSON with the
        // credentials in the body, which the authorization server rejects
        // ("OAuth 2.0 Parameter: grant_type" / invalid_client).
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(cscProperties.getOauth2().getClientId(),
                cscProperties.getOauth2().getClientSecret());

        @SuppressWarnings("unchecked")
        var response = restTemplate.postForObject(
                cscProperties.getOauth2().getTokenUrl(),
                new HttpEntity<>(form, headers), Map.class);
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
