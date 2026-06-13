package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CscCredentialInfoRequest {
    private String credentialID;
    private String certificates = "chain";
    private boolean certInfo = true;
    private boolean authInfo = true;
}
