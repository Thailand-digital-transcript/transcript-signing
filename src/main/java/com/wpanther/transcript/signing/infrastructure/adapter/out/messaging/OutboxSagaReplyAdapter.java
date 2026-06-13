package com.wpanther.transcript.signing.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.outbox.OutboxEvent;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.saga.domain.outbox.OutboxStatus;
import com.wpanther.transcript.signing.application.dto.event.TranscriptSigningReplyEvent;
import com.wpanther.transcript.signing.application.port.out.SagaReplyPort;
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
public class OutboxSagaReplyAdapter implements SagaReplyPort {

    private static final String AGGREGATE_TYPE = "TranscriptSigningReply";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTopicProperties topics;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                               String signedDocUrl, long signedDocSize, SigningFormat format) {
        var event = TranscriptSigningReplyEvent.success(sagaId, sagaStep, correlationId,
                signedDocUrl, signedDocSize, format);
        saveOutbox(event, sagaId, correlationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishFailure(String sagaId, SagaStep sagaStep, String correlationId,
                               String errorMessage) {
        var event = TranscriptSigningReplyEvent.failure(sagaId, sagaStep, correlationId, errorMessage);
        saveOutbox(event, sagaId, correlationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        var event = TranscriptSigningReplyEvent.compensated(sagaId, sagaStep, correlationId);
        saveOutbox(event, sagaId, correlationId);
    }

    private void saveOutbox(TranscriptSigningReplyEvent event, String sagaId, String correlationId) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize saga reply event", e);
        }
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(sagaId)
                .eventType(event.getEventType())
                .payload(payload)
                .createdAt(Instant.now())
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .topic(topics.getSagaReplyTranscriptSigning())
                .partitionKey(sagaId)
                .headers(headersJson(sagaId, correlationId))
                .build();
        outboxEventRepository.save(outboxEvent);
    }

    private String headersJson(String sagaId, String correlationId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("sagaId", sagaId, "correlationId", correlationId));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize outbox headers", e);
            return "{}";
        }
    }
}
