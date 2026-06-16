package com.wpanther.transcript.signing.application.dto;

import java.time.Instant;

public record SignHashResult(String transactionId, String pendingSignature, String certificate,
                             String sigId, Instant signingTime) {}
