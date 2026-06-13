package com.wpanther.transcript.signing.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompensateTranscriptSigningCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private final String documentId;

    @JsonCreator
    public CompensateTranscriptSigningCommand(
            @JsonProperty("eventId")       UUID eventId,
            @JsonProperty("occurredAt")    Instant occurredAt,
            @JsonProperty("eventType")     String eventType,
            @JsonProperty("version")       Integer version,
            @JsonProperty("sagaId")        String sagaId,
            @JsonProperty("sagaStep")      SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId")    String documentId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
    }

    public String getDocumentId() { return documentId; }
}
