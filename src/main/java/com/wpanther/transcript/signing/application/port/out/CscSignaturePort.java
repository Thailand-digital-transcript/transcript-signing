package com.wpanther.transcript.signing.application.port.out;

public interface CscSignaturePort {
    String signHash(String hashBase64, String sadToken, String credentialId, String hashAlgorithmOid);
}
