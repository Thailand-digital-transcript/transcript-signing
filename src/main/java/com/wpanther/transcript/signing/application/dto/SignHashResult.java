package com.wpanther.transcript.signing.application.dto;

public record SignHashResult(String transactionId, String pendingSignature, String certificate) {}
