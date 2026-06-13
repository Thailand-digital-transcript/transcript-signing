package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.CompensateTranscriptSigningCommand;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TranscriptSigningCompensationIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;

    KafkaTestHelper kafkaHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
    }

    @Test
    void compensationCommand_noExistingDocument_publishesCompensatedReply() throws Exception {
        var command = new CompensateTranscriptSigningCommand(null, null, null, null,
                "saga-comp-001", SagaStep.SIGN_XML, "corr-comp-001",
                "doc-comp-never-existed");

        kafkaHelper.sendCommand(topics.getSagaCompensationTranscriptSigning(), command);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var reply = kafkaHelper.pollOne(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2));
            assertThat(reply).isPresent();
            assertThat(reply.get().value()).contains("COMPENSATED");
        });
    }
}
