package com.wpanther.transcript.signing.infrastructure.adapter.in.camel;

import com.wpanther.saga.domain.outbox.OutboxEvent;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.saga.domain.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Outbox relay route — periodically reads PENDING events from the outbox table
 * and publishes them to Kafka. After successful publish, marks the event PUBLISHED.
 *
 * <p>Implements Phase 6 of the spec ("Outbox relay: Camel Kafka producer publishes
 * all three outbox events after DB commit").
 *
 * <p>Enabled in integration tests so that outbox entries written by the service reach
 * the Kafka reply topics that the IT assertions poll.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayRouteConfig extends RouteBuilder {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxRepository;

    @Override
    public void configure() {
        // Poll every 5 seconds and relay any PENDING events to Kafka
        from("timer:outbox-relay?period=5000&delay=2000")
                .routeId("outbox-relay")
                .process(this::drainPending)
                .split(body())
                .process(this::publishOne)
                .end();
    }

    private void drainPending(Exchange exchange) {
        List<OutboxEvent> pending = outboxRepository.findPendingEvents(BATCH_SIZE);
        if (pending.isEmpty()) {
            log.trace("Outbox relay: no pending events");
            exchange.getIn().setBody(List.of());
        } else {
            log.debug("Outbox relay: {} pending events to publish", pending.size());
            exchange.getIn().setBody(pending);
        }
    }

    private void publishOne(Exchange exchange) {
        OutboxEvent event = exchange.getIn().getBody(OutboxEvent.class);
        if (event == null || event.getTopic() == null || event.getTopic().isBlank()) {
            log.warn("Outbox event {} has no topic — skipping", event == null ? "null" : event.getId());
            return;
        }
        try {
            String kafkaEndpoint = String.format("kafka:%s?brokers={{camel.component.kafka.brokers}}",
                    event.getTopic());
            String key = event.getPartitionKey() != null
                    ? event.getPartitionKey()
                    : event.getAggregateId();
            exchange.getContext().createProducerTemplate()
                    .sendBodyAndHeader(kafkaEndpoint, event.getPayload(), "kafka.KEY", key);

            // Mark PUBLISHED by rebuilding the event with updated status and timestamp,
            // then saving through the repository. OutboxEvent is immutable (final fields)
            // so a fresh instance is required.
            OutboxEvent published = OutboxEvent.builder()
                    .id(event.getId())
                    .aggregateType(event.getAggregateType())
                    .aggregateId(event.getAggregateId())
                    .eventType(event.getEventType())
                    .payload(event.getPayload())
                    .createdAt(event.getCreatedAt())
                    .publishedAt(Instant.now())
                    .status(OutboxStatus.PUBLISHED)
                    .retryCount(event.getRetryCount())
                    .errorMessage(event.getErrorMessage())
                    .topic(event.getTopic())
                    .partitionKey(event.getPartitionKey())
                    .headers(event.getHeaders())
                    .build();
            outboxRepository.save(published);
            log.debug("Outbox event {} published to topic {}", event.getId(), event.getTopic());
        } catch (Exception e) {
            log.error("Failed to publish outbox event {} to topic {}: {}",
                    event.getId(), event.getTopic(), e.getMessage(), e);
        }
    }
}
