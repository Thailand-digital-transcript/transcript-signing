package com.wpanther.transcript.signing.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.transcript.signing.domain.model.SigningFormat;

import java.time.Instant;
import java.util.UUID;

public class DocumentArchiveEvent {

    private final String eventId;
    private final Instant occurredAt;
    private final String documentId;
    private final String documentNumber;
    private final SigningFormat format;
    private final String signedDocPath;
    private final Instant signedAt;

    @JsonCreator
    public DocumentArchiveEvent(
            @JsonProperty("eventId")       String eventId,
            @JsonProperty("occurredAt")    Instant occurredAt,
            @JsonProperty("documentId")    String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("format")        SigningFormat format,
            @JsonProperty("signedDocPath") String signedDocPath,
            @JsonProperty("signedAt")      Instant signedAt) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.format = format;
        this.signedDocPath = signedDocPath;
        this.signedAt = signedAt;
    }

    public static DocumentArchiveEvent of(String documentId, String documentNumber,
                                           SigningFormat format, String signedDocPath,
                                           Instant signedAt) {
        return new DocumentArchiveEvent(UUID.randomUUID().toString(), Instant.now(),
                documentId, documentNumber, format, signedDocPath, signedAt);
    }

    public String getEventId()        { return eventId; }
    public Instant getOccurredAt()    { return occurredAt; }
    public String getDocumentId()     { return documentId; }
    public String getDocumentNumber() { return documentNumber; }
    public SigningFormat getFormat()  { return format; }
    public String getSignedDocPath()  { return signedDocPath; }
    public Instant getSignedAt()      { return signedAt; }
}
