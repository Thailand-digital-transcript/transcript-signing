package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CscAuthorizeResponse {
    // The eidasremotesigning CSC returns the SAD under the lowercase key "sad"
    // in the authorize response. Jackson is case-sensitive, so this must match
    // exactly or the SAD deserialises to null (→ CSC_AUTH_EMPTY_SAD).
    @JsonProperty("sad")
    private String sad;
    private long expiresIn;
}
