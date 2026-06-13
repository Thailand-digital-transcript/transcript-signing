package com.wpanther.transcript.signing.application.dto;

public record PadesDigestResult(
        byte[] preparedPdfBytes,
        long[] byteRange,
        String signedAttrsDigestBase64,
        byte[] encodedSignedAttrs) {}
