package com.wpanther.transcript.signing.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.outbox.OutboxEvent;
import com.wpanther.transcript.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.transcript.saga.domain.outbox.OutboxStatus;
import com.wpanther.transcript.saga.infrastructure.outbox.OutboxService;
import com.wpanther.transcript.signing.infrastructure.persistence.outbox.OutboxEventEntity;
import com.wpanther.transcript.signing.infrastructure.persistence.outbox.SpringDataOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class OutboxConfig {

    @Bean
    @ConditionalOnMissingBean
    public OutboxService outboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        return new OutboxService(repository, objectMapper);
    }

    /**
     * Adapts our Spring Data outbox repository to the {@link OutboxEventRepository} interface
     * expected by saga-commons {@link OutboxService}. Maps between the immutable saga-commons
     * domain class {@link OutboxEvent} and our mutable JPA entity
     * {@link OutboxEventEntity}.
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxEventRepository outboxEventRepository(SpringDataOutboxRepository jpa) {
        return new OutboxEventRepository() {
            @Override
            public OutboxEvent save(OutboxEvent event) {
                OutboxEventEntity entity = toEntity(event);
                if (entity.getId() == null) {
                    entity.setId(event.getId() != null ? event.getId() : UUID.randomUUID());
                }
                return toDomain(jpa.save(entity));
            }

            @Override
            public Optional<OutboxEvent> findById(UUID id) {
                return jpa.findById(id).map(OutboxConfig::toDomain);
            }

            @Override
            public List<OutboxEvent> findPendingEvents(int limit) {
                return jpa.findByStatusOrderByCreatedAtAsc(
                                OutboxStatus.PENDING.name(), PageRequest.of(0, limit))
                        .stream()
                        .map(OutboxConfig::toDomain)
                        .toList();
            }

            @Override
            public List<OutboxEvent> findFailedEvents(int limit) {
                return jpa.findByStatusOrderByCreatedAtAsc(
                                OutboxStatus.FAILED.name(), PageRequest.of(0, limit))
                        .stream()
                        .map(OutboxConfig::toDomain)
                        .toList();
            }

            @Override
            public List<OutboxEvent> findByAggregate(String aggregateType, String aggregateId) {
                return jpa.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                                aggregateType, aggregateId)
                        .stream()
                        .map(OutboxConfig::toDomain)
                        .toList();
            }

            @Override
            public int deletePublishedBefore(Instant before) {
                jpa.deletePublishedBefore(before);
                return 0; // saga-commons OutboxCleanupService only checks the return for logging
            }
        };
    }

    // --- mapping helpers ---

    private static OutboxEventEntity toEntity(OutboxEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setId(event.getId());
        entity.setAggregateType(event.getAggregateType());
        entity.setAggregateId(event.getAggregateId());
        entity.setEventType(event.getEventType());
        entity.setPayload(event.getPayload());
        entity.setCreatedAt(event.getCreatedAt());
        entity.setPublishedAt(event.getPublishedAt());
        entity.setStatus(event.getStatus() != null ? event.getStatus().name() : null);
        entity.setRetryCount(event.getRetryCount());
        entity.setErrorMessage(event.getErrorMessage());
        entity.setTopic(event.getTopic());
        entity.setPartitionKey(event.getPartitionKey());
        entity.setHeaders(event.getHeaders());
        return entity;
    }

    private static OutboxEvent toDomain(OutboxEventEntity entity) {
        OutboxStatus status = entity.getStatus() != null
                ? OutboxStatus.valueOf(entity.getStatus())
                : OutboxStatus.PENDING;
        return OutboxEvent.builder()
                .id(entity.getId())
                .aggregateType(entity.getAggregateType())
                .aggregateId(entity.getAggregateId())
                .eventType(entity.getEventType())
                .payload(entity.getPayload())
                .createdAt(entity.getCreatedAt())
                .publishedAt(entity.getPublishedAt())
                .status(status)
                .retryCount(entity.getRetryCount())
                .errorMessage(entity.getErrorMessage())
                .topic(entity.getTopic())
                .partitionKey(entity.getPartitionKey())
                .headers(entity.getHeaders())
                .build();
    }
}
