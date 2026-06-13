package com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CscAuthorizeRequest {
    private String credentialID;
    private int numSignatures = 1;
    private List<String> hash;
    private String hashAlgorithmOID;
    private String description;
    private List<AuthData> authData;

    @Getter @Setter
    public static class AuthData {
        private String id;
        private String value;

        public static AuthData pin(String pinValue) {
            AuthData a = new AuthData();
            a.id = "PIN";
            a.value = pinValue;
            return a;
        }
    }
}
