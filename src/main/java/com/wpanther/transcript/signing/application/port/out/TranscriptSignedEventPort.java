package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.domain.model.SigningFormat;

import java.time.Instant;

public interface TranscriptSignedEventPort {
    void publish(String documentId, String documentNumber, SigningFormat format,
                 String signedDocUrl, String signatureLevel, Instant signatureTimestamp);
}
