package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.domain.model.SigningFormat;

import java.time.Instant;

public interface DocumentArchivePort {
    void publish(String documentId, String documentNumber, SigningFormat format,
                 String signedDocPath, Instant signedAt);
}
