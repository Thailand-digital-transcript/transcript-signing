package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CscAuthorizeResponse {
    // CSC API returns "SAD" (uppercase) — Jackson is case-sensitive by default,
    // so the annotation is required to deserialize the field correctly.
    @JsonProperty("SAD")
    private String sad;
    private long expiresIn;
}
