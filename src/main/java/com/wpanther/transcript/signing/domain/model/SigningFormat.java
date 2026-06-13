package com.wpanther.transcript.signing.domain.model;

public enum SigningFormat {
    XML, PDF;

    public String fileExtension() {
        return name().toLowerCase();
    }
}
