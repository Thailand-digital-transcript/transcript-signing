package com.wpanther.transcript.signing.application.dto;

public record PadesDigestResult(
        byte[] preparedPdfBytes,
        String signedAttrsDigestBase64,
        byte[] encodedSignedAttrs) {}
