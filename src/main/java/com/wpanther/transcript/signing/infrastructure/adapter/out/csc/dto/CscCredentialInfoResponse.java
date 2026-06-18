package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CscCredentialInfoResponse {
    private Cert cert;
    private Key key;

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cert {
        private List<String> certificates;
        private String status;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Key {
        // CSC v2 returns key.algo as an array of signature-algorithm OIDs.
        private List<String> algo;
        private int len;
    }
}
