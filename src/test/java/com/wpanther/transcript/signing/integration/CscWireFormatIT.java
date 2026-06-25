package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CscWireFormatIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;

    KafkaTestHelper kafkaHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        stubCscOAuth2Token();
        stubCscCredentialInfo();
        stubCscAuthorizeWithSad("");  // blank SAD — production blank-SAD guard rejects this
        // No signHash stub is registered. It is never reached because the adapter throws
        // CSC_AUTH_EMPTY_SAD at the authorize step before signHash is ever invoked.
    }

    @Test
    void saga_failsAtAuthorize_whenSadIsBlank() throws Exception {
        String xmlContent = "<Transcript><DocumentID>doc-wire-001</DocumentID></Transcript>";
        var command = new ProcessTranscriptSigningCommand(null, null, null, null,
                "saga-wire-001", SagaStep.SIGN_XML, "corr-wire-001",
                "doc-wire-001", "TH-2026-WIRE-001", SigningFormat.XML, xmlContent, null);

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigning(), command);

        // CscAuthorizationAdapter has a production guard (response.getSad().isBlank() →
        // SigningException("CSC_AUTH_EMPTY_SAD", "CSC authorize returned empty SAD token"))
        // that fires BEFORE signHash is ever called. So the saga fails at the authorize
        // step with a FAILURE reply carrying the "empty SAD" error message, and signHash
        // is never attempted (no unmatched request will appear for /signatures/signHash).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), "saga-wire-001");
            assertThat(reply).as("FAILURE reply for saga-wire-001 must arrive").isPresent();
            String value = reply.get().value();
            assertThat(value).contains("FAILURE");
            assertThat(value)
                    .as("error message must surface the blank-SAD guard (CSC_AUTH_EMPTY_SAD / empty SAD)")
                    .containsAnyOf("CSC_AUTH_EMPTY_SAD", "empty SAD");
        });
    }
}
