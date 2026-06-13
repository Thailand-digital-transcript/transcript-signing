package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class CscSignHashRequest {
    private String credentialID;
    private String SAD;
    private List<String> hash;
    private String hashAlgorithmOID;
    private String signAlgo;
    private String signAlgoParams;
}
