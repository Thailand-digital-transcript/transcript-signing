package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CscSignHashRequest {
    private String credentialID;
    // CSC v2 wire format requires uppercase "SAD". Without this annotation Jackson
    // derives the key from getSAD() and lowercases all leading uppercase chars → "sad",
    // which the eidasremotesigning endpoint reads as null (case-sensitive @JsonProperty).
    @JsonProperty("SAD")
    private String SAD;
    // CSC v2 expects the hash array under "hashes".
    @JsonProperty("hashes")
    private List<String> hash;
    private String hashAlgorithmOID;
    private String signAlgo;
    private String signAlgoParams;
}
