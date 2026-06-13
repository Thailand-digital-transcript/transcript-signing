package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TranscriptSigningPipelineIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;

    KafkaTestHelper kafkaHelper;

    @BeforeEach
    void setUpKafka() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        stubCscCredentialInfo();
        stubCscOAuth2Token();
    }

    @Test
    void xmlSigningCommand_happyPath_producesSuccessReplyAndSignedEvent() throws Exception {
        stubCscAuthorize();
        stubCscSignHash();

        String xmlContent = "<Transcript><DocumentID>doc-it-001</DocumentID></Transcript>";
        var command = new ProcessTranscriptSigningCommand(null, null, null, null,
                "saga-it-001", SagaStep.SIGN_XML, "corr-it-001",
                "doc-it-001", "TH-2026-IT-001", SigningFormat.XML, xmlContent, null);

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigning(), command);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var reply = kafkaHelper.pollOne(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2));
            assertThat(reply).isPresent();
            assertThat(reply.get().value()).contains("SUCCESS");
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var event = kafkaHelper.pollOne(topics.getTranscriptSigned(), Duration.ofSeconds(2));
            assertThat(event).isPresent();
            assertThat(event.get().value()).contains("doc-it-001");
        });
    }

    private void stubCscOAuth2Token() {
        wireMock.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-token\",\"expires_in\":3600}")));
    }

    private void stubCscCredentialInfo() {
        wireMock.stubFor(post(urlEqualTo("/csc/v1/credentials/info"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"cert\":{\"certificates\":[\"" + fakeCertBase64() + "\"]},\"key\":{\"algo\":\"RSA\"}}")));
    }

    private void stubCscAuthorize() {
        wireMock.stubFor(post(urlEqualTo("/csc/v1/credentials/authorize"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"SAD\":\"sad-token-it\",\"expiresIn\":60}")));
    }

    private void stubCscSignHash() {
        String fakeSig = Base64.getEncoder().encodeToString(new byte[256]);
        wireMock.stubFor(post(urlEqualTo("/csc/v1/signatures/signHash"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"" + fakeSig + "\"]}")));
    }

    private String fakeCertBase64() {
        return Base64.getEncoder().encodeToString(new byte[512]);
    }
}
