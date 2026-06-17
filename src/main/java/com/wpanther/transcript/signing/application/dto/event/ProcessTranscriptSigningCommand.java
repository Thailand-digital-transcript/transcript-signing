package com.wpanther.transcript.signing.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.saga.domain.model.SagaCommand;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessTranscriptSigningCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @NotBlank @Size(max = 100)
    private final String documentId;

    @NotBlank @Size(max = 50)
    private final String documentNumber;

    @NotNull
    private final SigningFormat format;

    private final String xmlContent;
    private final String pdfUrl;

    @JsonCreator
    public ProcessTranscriptSigningCommand(
            @JsonProperty("eventId")       UUID eventId,
            @JsonProperty("occurredAt")    Instant occurredAt,
            @JsonProperty("eventType")     String eventType,
            @JsonProperty("version")       Integer version,
            @JsonProperty("sagaId")        String sagaId,
            @JsonProperty("sagaStep")      SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId")    String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("format")        SigningFormat format,
            @JsonProperty("xmlContent")    String xmlContent,
            @JsonProperty("pdfUrl")        String pdfUrl) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.format = format;
        this.xmlContent = xmlContent;
        this.pdfUrl = pdfUrl;
    }

    public String getDocumentId()     { return documentId; }
    public String getDocumentNumber() { return documentNumber; }
    public SigningFormat getFormat()  { return format; }
    public String getXmlContent()    { return xmlContent; }
    public String getPdfUrl()        { return pdfUrl; }
}
