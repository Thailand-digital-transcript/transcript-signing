package com.wpanther.transcript.signing.application.port.out;

public interface CscAuthorizationPort {
    String authorize(String credentialId, String hashBase64, String pin);
}
