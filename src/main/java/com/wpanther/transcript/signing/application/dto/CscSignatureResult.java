package com.wpanther.transcript.signing.application.dto;

import java.util.List;

/**
 * Result from a CSC signHash operation containing the signature(s) and the CSC transaction id.
 * The transactionId (responseID from CSC) is used for correlation and persisted at the TX1.5 checkpoint.
 */
public record CscSignatureResult(String transactionId, List<String> signatures) {

    /** Convenience for single-signature calls (1A path). Returns the first and only signature. */
    public String getSingleSignature() {
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalStateException("No signatures in CSC response");
        }
        if (signatures.size() > 1) {
            throw new IllegalStateException("Expected single signature, got " + signatures.size());
        }
        return signatures.get(0);
    }
}
