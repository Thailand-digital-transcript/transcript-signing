package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.domain.model.SignerRole;
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
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The only IT covering the batch PAdES (PDF) round. BatchSigningPipelineIT and
 * BatchSigningResumeIT both use SigningFormat.XML, so without this the batch PDF phase
 * has no integration coverage.
 *
 * <p>Unlike the XML rounds, the PDF round fetches its source over HTTP
 * ({@code BatchSigningCommandHandler.downloadSource()} branches on
 * {@code format == SigningFormat.PDF} and calls {@code documentDownloadPort}), so the
 * item's storageKey is a URL, not a bucket-relative MinIO key.
 */
class BatchSigningPdfPipelineIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;
    @Autowired StorageProperties storageProperties;

    KafkaTestHelper kafkaHelper;
    MinioTestHelper minioHelper;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        minioHelper = new MinioTestHelper(
                MINIO.getS3URL(), "minioadmin", "minioadmin",
                storageProperties.getBucketName());
        stubCscOAuth2Token();
        stubCscCredentialInfo();
        stubCscAuthorize();
        stubCscSignHashFakeSig();
        stubPdfDownload();
    }

    @Test
    void batchPdfSigning_happyPath_producesSuccessReplyAndUploadsSignedPdf() throws Exception {
        String docId = "doc-batch-pdf-" + UUID.randomUUID().toString().substring(0, 8);
        String sagaId = "saga-batch-pdf-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "corr-batch-pdf-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId = "batch-pdf-" + UUID.randomUUID().toString().substring(0, 8);
        String pdfUrl = "http://localhost:" + wireMock.port() + "/sample.pdf";
        String signedKey = String.format("PDF/%s/%s/signed.pdf", batchId, docId);

        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_PDF, correlationId, batchId,
                SignerRole.SEAL, SigningFormat.PDF,
                List.of(new BatchSigningCommand.Item(docId, "NUM-" + docId, pdfUrl)));

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), sagaId);
            assertThat(reply).as("SUCCESS reply for %s must arrive", sagaId).isPresent();
            String value = reply.get().value();
            assertThat(value).contains("SUCCESS");
            assertThat(value).contains(docId);
        });

        assertThat(minioHelper.objectExists(signedKey))
                .as("PAdES-signed PDF must be uploaded to %s", signedKey)
                .isTrue();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/csc/v2/signatures/signHash")));
    }

    private void stubPdfDownload() {
        wireMock.stubFor(get(urlEqualTo("/sample.pdf"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(createMinimalPdf())));
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
}
