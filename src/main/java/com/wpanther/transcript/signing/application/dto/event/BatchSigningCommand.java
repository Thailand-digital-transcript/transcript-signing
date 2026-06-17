package com.wpanther.transcript.signing.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.saga.domain.model.SagaCommand;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchSigningCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @NotNull private final String batchId;
    @NotNull private final SignerRole signerRole;
    @NotNull private final SigningFormat format;
    @NotEmpty private final List<Item> items;

    @JsonCreator
    public BatchSigningCommand(
            @JsonProperty("eventId")       UUID eventId,
            @JsonProperty("occurredAt")    Instant occurredAt,
            @JsonProperty("eventType")     String eventType,
            @JsonProperty("version")       Integer version,
            @JsonProperty("sagaId")        String sagaId,
            @JsonProperty("sagaStep")      SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("batchId")       String batchId,
            @JsonProperty("signerRole")    SignerRole signerRole,
            @JsonProperty("format")        SigningFormat format,
            @JsonProperty("items")         List<Item> items) {
        super(sagaId, sagaStep, correlationId);
        this.batchId = batchId;
        this.signerRole = signerRole;
        this.format = format;
        this.items = items;
    }

    public String getBatchId()        { return batchId; }
    public SignerRole getSignerRole() { return signerRole; }
    public SigningFormat getFormat()  { return format; }
    public List<Item> getItems()      { return items; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private final String documentId;
        private final String documentNumber;
        private final String storageKey;

        @JsonCreator
        public Item(@JsonProperty("documentId") String documentId,
                    @JsonProperty("documentNumber") String documentNumber,
                    @JsonProperty("storageKey") String storageKey) {
            this.documentId = documentId;
            this.documentNumber = documentNumber;
            this.storageKey = storageKey;
        }

        public String getDocumentId()     { return documentId; }
        public String getDocumentNumber() { return documentNumber; }
        public String getStorageKey()     { return storageKey; }
    }
}
