package com.wpanther.transcript.signing.infrastructure.config;

import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class FeignClientConfig {

    private final CscProperties cscProperties;

    @Bean
    public RequestInterceptor cscOauth2Interceptor(CscTokenProvider tokenProvider) {
        return requestTemplate ->
                requestTemplate.header("Authorization", "Bearer " + tokenProvider.getAccessToken());
    }
}
