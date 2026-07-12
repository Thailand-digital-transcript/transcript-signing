package com.wpanther.transcript.signing.infrastructure.adapter.in.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.application.usecase.BatchSagaCommandPort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaRouteConfig extends RouteBuilder {

    private final BatchSagaCommandPort batchSagaCommandPort;
    private final ObjectMapper objectMapper;
    private final CommandValidator commandValidator;
    private final KafkaTopicProperties topics;

    @Override
    public void configure() {
        // Dead-letter the bytes we consumed from Kafka, not the current exchange body.
        // By the time a failure surfaces the body is the unmarshalled BatchSigningCommand,
        // and the Kafka StringSerializer would render it via toString() — leaving the DLQ
        // holding "BatchSigningCommand(super=...)", which cannot be parsed or replayed.
        // The original message is also the only faithful copy: the DTO is
        // @JsonIgnoreProperties(ignoreUnknown = true), so re-serialising it would silently
        // drop any field this service does not model.
        // A disallowed source bucket (S3StorageAdapter.download) is a PERMANENT failure: it
        // signals a bug or compromise upstream in the orchestrator, and no number of
        // redeliveries changes the answer. This clause must be declared before the general
        // onException(Exception.class) below — Camel evaluates onException clauses for the
        // same exception type in declaration order, first match wins — so it DLQs on the
        // first attempt instead of after four (0 redeliveries vs. the general 3).
        onException(SigningException.class)
                .onWhen(exchange -> {
                    Throwable caught = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                    return caught instanceof SigningException se
                            && "STORAGE_BUCKET_NOT_ALLOWED".equals(se.getErrorCode());
                })
                .maximumRedeliveries(0)
                .log(LoggingLevel.ERROR, "Permanent failure (disallowed source bucket) — "
                        + "routing to DLQ without redelivery: ${exception.message}")
                .handled(true)
                .useOriginalMessage()
                .to("kafka:" + topics.getDlq() + buildKafkaProducerOptions());

        onException(Exception.class)
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .backOffMultiplier(3)
                .maximumRedeliveryDelay(10_000)
                .log(LoggingLevel.ERROR, "Routing to DLQ after ${exception.message}")
                .handled(true)
                .useOriginalMessage()
                .to("kafka:" + topics.getDlq() + buildKafkaProducerOptions());

        from(kafkaConsumerUrl(topics.getSagaCommandTranscriptSigningBatch()))
                .routeId("transcript-signing-batch-command")
                .process(this::unmarshalBatchCommand)
                .process(commandValidator)
                .process(exchange -> batchSagaCommandPort.handleBatchSigning(
                        exchange.getIn().getBody(BatchSigningCommand.class)));
    }

    private void unmarshalBatchCommand(Exchange exchange) throws Exception {
        String body = exchange.getIn().getBody(String.class);
        exchange.getIn().setBody(objectMapper.readValue(body, BatchSigningCommand.class));
    }

    private String kafkaConsumerUrl(String topic) {
        return "kafka:" + topic
                + "?brokers={{camel.component.kafka.brokers}}"
                + "&groupId=transcript-signing-group"
                + "&autoOffsetReset=earliest"
                + "&autoCommitEnable=false"
                + "&breakOnFirstError=true"
                + "&maxPollRecords=100"
                + "&pollTimeoutMs=5000"
                + "&shutdownTimeout=5000"
                + "&allowManualCommit=true";
    }

    private String buildKafkaProducerOptions() {
        return "?brokers={{camel.component.kafka.brokers}}";
    }
}
