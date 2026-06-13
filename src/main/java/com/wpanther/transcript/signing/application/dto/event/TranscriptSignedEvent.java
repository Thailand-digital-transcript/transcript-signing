package com.wpanther.transcript.signing.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.transcript.signing.domain.model.SigningFormat;

import java.time.Instant;
import java.util.UUID;

public class TranscriptSignedEvent {

    private final String eventId;
    private final Instant occurredAt;
    private final String documentId;
    private final String documentNumber;
    private final SigningFormat format;
    private final String signedDocUrl;
    private final String signatureLevel;
    private final Instant signatureTimestamp;

    @JsonCreator
    public TranscriptSignedEvent(
            @JsonProperty("eventId")            String eventId,
            @JsonProperty("occurredAt")         Instant occurredAt,
            @JsonProperty("documentId")         String documentId,
            @JsonProperty("documentNumber")     String documentNumber,
            @JsonProperty("format")             SigningFormat format,
            @JsonProperty("signedDocUrl")       String signedDocUrl,
            @JsonProperty("signatureLevel")     String signatureLevel,
            @JsonProperty("signatureTimestamp") Instant signatureTimestamp) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.format = format;
        this.signedDocUrl = signedDocUrl;
        this.signatureLevel = signatureLevel;
        this.signatureTimestamp = signatureTimestamp;
    }

    public static TranscriptSignedEvent of(String documentId, String documentNumber,
                                            SigningFormat format, String signedDocUrl,
                                            String signatureLevel, Instant signatureTimestamp) {
        return new TranscriptSignedEvent(UUID.randomUUID().toString(), Instant.now(),
                documentId, documentNumber, format, signedDocUrl, signatureLevel, signatureTimestamp);
    }

    public String getEventId()               { return eventId; }
    public Instant getOccurredAt()           { return occurredAt; }
    public String getDocumentId()            { return documentId; }
    public String getDocumentNumber()        { return documentNumber; }
    public SigningFormat getFormat()         { return format; }
    public String getSignedDocUrl()          { return signedDocUrl; }
    public String getSignatureLevel()        { return signatureLevel; }
    public Instant getSignatureTimestamp()   { return signatureTimestamp; }
}
