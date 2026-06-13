package com.wpanther.transcript.signing.infrastructure.adapter.in.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.signing.application.dto.event.CompensateTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.usecase.SagaCommandPort;
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

    private final SagaCommandPort sagaCommandPort;
    private final ObjectMapper objectMapper;
    private final CommandValidator commandValidator;
    private final KafkaTopicProperties topics;

    @Override
    public void configure() {
        onException(Exception.class)
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .backOffMultiplier(3)
                .maximumRedeliveryDelay(10_000)
                .log(LoggingLevel.ERROR, "Routing to DLQ after ${exception.message}")
                .handled(true)
                .to("kafka:" + topics.getDlq() + buildKafkaProducerOptions());

        from(kafkaConsumerUrl(topics.getSagaCommandTranscriptSigning()))
                .routeId("transcript-signing-command")
                .process(this::unmarshalSigningCommand)
                .process(commandValidator)
                .process(exchange -> sagaCommandPort.handleSigningCommand(
                        exchange.getIn().getBody(ProcessTranscriptSigningCommand.class)));

        from(kafkaConsumerUrl(topics.getSagaCompensationTranscriptSigning()))
                .routeId("transcript-signing-compensation")
                .process(this::unmarshalCompensationCommand)
                .process(commandValidator)
                .process(exchange -> sagaCommandPort.handleCompensationCommand(
                        exchange.getIn().getBody(CompensateTranscriptSigningCommand.class)));
    }

    private void unmarshalSigningCommand(Exchange exchange) throws Exception {
        String body = exchange.getIn().getBody(String.class);
        ProcessTranscriptSigningCommand command =
                objectMapper.readValue(body, ProcessTranscriptSigningCommand.class);
        exchange.getIn().setBody(command);
    }

    private void unmarshalCompensationCommand(Exchange exchange) throws Exception {
        String body = exchange.getIn().getBody(String.class);
        CompensateTranscriptSigningCommand command =
                objectMapper.readValue(body, CompensateTranscriptSigningCommand.class);
        exchange.getIn().setBody(command);
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
