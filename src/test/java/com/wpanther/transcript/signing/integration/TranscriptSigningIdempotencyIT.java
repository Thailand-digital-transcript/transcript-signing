package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import com.wpanther.transcript.signing.integration.support.MinioTestHelper;
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

/**
 * Verifies the spec's most important crash-safety property: replaying a
 * ProcessTranscriptSigningCommand for a document that is already COMPLETED
 * republishes a SUCCESS reply from the DB state without re-invoking CSC,
 * re-uploading the original, or producing a second signed file.
 */
class TranscriptSigningIdempotencyIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;
    @Autowired StorageProperties storageProperties;

    KafkaTestHelper kafkaHelper;
    MinioTestHelper minioHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        minioHelper = new MinioTestHelper(
                MINIO.getS3URL(), "minioadmin", "minioadmin",
                storageProperties.getBucketName());
        stubCscOAuth2Token();
        stubCscCredentialInfo();
        stubCscAuthorize();
        stubCscSignHash();
        stubPdfDownload();
    }

    @Test
    void secondCommandForCompletedDocument_republishesSuccess_doesNotResign() throws Exception {
        var firstCommand = new ProcessTranscriptSigningCommand(null, null, null, null,
                "saga-idem-001", SagaStep.SIGN_PDF, "corr-idem-001",
                "doc-idem-001", "TH-2026-IDEM-001", SigningFormat.PDF, null,
                "http://localhost:" + wireMock.port() + "/sample.pdf");

        // First command: full pipeline → COMPLETED in DB
        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigning(), firstCommand);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), "saga-idem-001");
            assertThat(reply).isPresent();
            assertThat(reply.get().value()).contains("SUCCESS");
        });

        // The Kafka reply is produced only after TX2 commits (COMPLETED in DB), so the
        // DB is already in COMPLETED state at this point. Reset the WireMock request
        // journal so second-command CSC invocations are counted from zero — this is the
        // clean boundary between the two commands without any fixed sleep.
        wireMock.resetRequests();

        // Second command: same document, NEW sagaId and correlationId. The handler
        // should see the existing COMPLETED record and republish a SUCCESS reply
        // without invoking CSC, downloading again, or writing a new signed file.
        var replayCommand = new ProcessTranscriptSigningCommand(null, null, null, null,
                "saga-idem-002", SagaStep.SIGN_PDF, "corr-idem-002",
                "doc-idem-001", "TH-2026-IDEM-001", SigningFormat.PDF, null,
                "http://localhost:" + wireMock.port() + "/sample.pdf");

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigning(), replayCommand);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), "saga-idem-002");
            assertThat(reply).isPresent();
            assertThat(reply.get().value()).contains("SUCCESS");
            // documentId in the reply confirms it is the same record, not a new one
            assertThat(reply.get().value()).contains("doc-idem-001");
        });

        // CSC must not have been called for the replay — the handler short-circuits at
        // the COMPLETED idempotency check before reaching any CSC port.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/csc/v1/signatures/signHash")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/csc/v1/credentials/authorize")));

        // S3 objects from the first command must still exist (no re-upload on replay).
        assertThat(minioHelper.objectExists("PDF/doc-idem-001/attempt-0/original.pdf"))
                .as("Original PDF should be uploaded exactly once on first attempt")
                .isTrue();
        assertThat(minioHelper.objectExists("PDF/doc-idem-001/attempt-0/signed.pdf"))
                .as("Signed PDF should be uploaded exactly once on first attempt")
                .isTrue();
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
                        .withBody("{\"SAD\":\"sad-idem-it\",\"expiresIn\":60}")));
    }

    private void stubCscSignHash() {
        String fakeSig = Base64.getEncoder().encodeToString(new byte[256]);
        wireMock.stubFor(post(urlEqualTo("/csc/v1/signatures/signHash"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"" + fakeSig + "\"]}")));
    }
}
