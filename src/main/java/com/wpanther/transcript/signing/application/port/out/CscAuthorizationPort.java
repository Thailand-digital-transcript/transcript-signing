package com.wpanther.transcript.signing.application.port.out;

import java.util.List;

public interface CscAuthorizationPort {
    /** Existing: authorize a single hash. */
    String authorize(String credentialId, String hashBase64, String pin);

    /** Batch: authorize N hashes under one SAD. */
    String authorize(String credentialId, List<String> hashesBase64, String pin);
}
