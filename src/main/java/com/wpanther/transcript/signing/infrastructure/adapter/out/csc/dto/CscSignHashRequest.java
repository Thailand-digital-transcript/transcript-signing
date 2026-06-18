package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class CscSignHashRequest {
    private String credentialID;
    private String SAD;
    // CSC v2 expects the hash array under "hashes".
    @JsonProperty("hashes")
    private List<String> hash;
    private String hashAlgorithmOID;
    private String signAlgo;
    private String signAlgoParams;
}
