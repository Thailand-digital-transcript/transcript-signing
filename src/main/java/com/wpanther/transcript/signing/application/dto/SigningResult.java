package com.wpanther.transcript.signing.application.dto;

import java.time.Instant;

public record SigningResult(
        String signedDocPath,
        String signedDocUrl,
        long signedDocSize,
        String signatureLevel,
        Instant signatureTimestamp) {}
