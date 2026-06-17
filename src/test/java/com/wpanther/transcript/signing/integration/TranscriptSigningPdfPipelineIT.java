package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TranscriptSigningPdfPipelineIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;

    KafkaTestHelper kafkaHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        stubCscOAuth2Token();
        stubCscCredentialInfo();
        stubCscAuthorize();
        stubCscSignHash();
        stubPdfDownload();
    }

    @Test
    void pdfSigningCommand_happyPath_producesSuccessReplyAndSignedEvent() throws Exception {
        var command = new ProcessTranscriptSigningCommand(null, null, null, null,
                "saga-pdf-it-001", SagaStep.SIGN_PDF, "corr-pdf-it-001",
                "doc-pdf-it-001", "TH-2026-PDF-IT-001", SigningFormat.PDF, null,
                "http://localhost:" + wireMock.port() + "/sample.pdf");

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigning(), command);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), "saga-pdf-it-001");
            assertThat(reply).isPresent();
            assertThat(reply.get().value()).contains("SUCCESS");
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var event = kafkaHelper.pollFor(topics.getTranscriptSigned(),
                    Duration.ofSeconds(2), "doc-pdf-it-001");
            assertThat(event).isPresent();
            assertThat(event.get().value()).contains("doc-pdf-it-001");
        });
    }

    private void stubPdfDownload() {
        byte[] minimalPdf = createMinimalPdf();
        wireMock.stubFor(get(urlEqualTo("/sample.pdf"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(minimalPdf)));
    }

    private byte[] createMinimalPdf() {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test PDF", e);
        }
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
                        .withBody("{\"cert\":{\"certificates\":[\"" +
                                TEST_CERT_DER_BASE64 +
                                "\"]},\"key\":{\"algo\":\"RSA\"}}")));
    }

    private void stubCscAuthorize() {
        wireMock.stubFor(post(urlEqualTo("/csc/v1/credentials/authorize"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"SAD\":\"sad-pdf-it\",\"expiresIn\":60}")));
    }

    private void stubCscSignHash() {
        String fakeSig = Base64.getEncoder().encodeToString(new byte[256]);
        wireMock.stubFor(post(urlEqualTo("/csc/v1/signatures/signHash"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"" + fakeSig + "\"]}")));
    }
}
