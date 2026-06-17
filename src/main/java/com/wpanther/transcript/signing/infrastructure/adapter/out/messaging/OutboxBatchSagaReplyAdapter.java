package com.wpanther.transcript.signing.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.saga.domain.outbox.OutboxEvent;
import com.wpanther.transcript.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.transcript.saga.domain.outbox.OutboxStatus;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningReplyEvent;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningReplyEvent.ItemResult;
import com.wpanther.transcript.signing.application.port.out.BatchSagaReplyPort;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxBatchSagaReplyAdapter implements BatchSagaReplyPort {

    private static final String AGGREGATE_TYPE = "BatchSigningReply";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTopicProperties topics;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishBatchReply(String sagaId, SagaStep sagaStep, String correlationId,
                                  String batchId, boolean allSucceeded, List<ItemResult> items) {
        buildOutbox(sagaId, sagaStep, correlationId, batchId, allSucceeded, items);
    }

    /**
     * Build the outbox row for a batch saga reply and persist it. Package-private so unit tests
     * can call it directly without an active transaction (the public method is
     * {@code @Transactional(MANDATORY)} and would throw
     * {@link IllegalTransactionStateException} outside a tx).
     *
     * @return the newly built (and saved) {@link OutboxEvent}
     */
    OutboxEvent buildOutbox(String sagaId, SagaStep sagaStep, String correlationId, String batchId,
                            boolean allSucceeded, List<ItemResult> items) {
        var event = BatchSigningReplyEvent.of(sagaId, sagaStep, correlationId, batchId,
                allSucceeded, items);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize batch signing reply", e);
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
                .headers(headers(sagaId, correlationId, batchId))
                .build();
        outboxEventRepository.save(outboxEvent);
        return outboxEvent;
    }

    private String headers(String sagaId, String correlationId, String batchId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("sagaId", sagaId, "correlationId", correlationId, "batchId", batchId));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
