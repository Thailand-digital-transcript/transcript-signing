package com.wpanther.transcript.signing.domain.model;

public class SigningException extends RuntimeException {

    private final String errorCode;

    public SigningException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SigningException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
