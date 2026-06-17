package com.wpanther.transcript.signing.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.outbox.OutboxEvent;
import com.wpanther.transcript.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.transcript.saga.domain.outbox.OutboxStatus;
import com.wpanther.transcript.signing.application.dto.event.DocumentArchiveEvent;
import com.wpanther.transcript.signing.application.port.out.DocumentArchivePort;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDocumentArchiveAdapter implements DocumentArchivePort {

    private static final String AGGREGATE_TYPE = "SignedTranscriptDocument";
    private static final String EVENT_TYPE = "DocumentArchive";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTopicProperties topics;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String documentId, String documentNumber, SigningFormat format,
                        String signedDocPath, Instant signedAt) {
        var event = DocumentArchiveEvent.of(documentId, documentNumber, format, signedDocPath, signedAt);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize document archive event", e);
        }
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(documentId)
                .eventType(EVENT_TYPE)
                .payload(payload)
                .createdAt(Instant.now())
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .topic(topics.getDocumentArchive())
                .partitionKey(documentId)
                .headers(headersJson(documentId))
                .build();
        outboxEventRepository.save(outboxEvent);
    }

    private String headersJson(String documentId) {
        try {
            return objectMapper.writeValueAsString(Map.of("documentId", documentId));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
