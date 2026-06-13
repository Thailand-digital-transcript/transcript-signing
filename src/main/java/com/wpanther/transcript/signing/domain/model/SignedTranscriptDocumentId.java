package com.wpanther.transcript.signing.domain.model;

import java.util.UUID;

public record SignedTranscriptDocumentId(UUID value) {
    public static SignedTranscriptDocumentId generate() {
        return new SignedTranscriptDocumentId(UUID.randomUUID());
    }

    public static SignedTranscriptDocumentId of(UUID uuid) {
        return new SignedTranscriptDocumentId(uuid);
    }
}
