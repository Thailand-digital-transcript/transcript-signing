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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins the CSC wire-format guard: {@code CscAuthorizationAdapter} rejects a blank SAD
 * ({@code CSC_AUTH_EMPTY_SAD}) BEFORE {@code signHash} is ever invoked. This matters
 * because the CSC API uses uppercase JSON keys ({@code "SAD"}); a missing
 * {@code @JsonProperty("SAD")} deserialises to null/blank and would otherwise bill the
 * HSM for a signature that can never be used.
 *
 * <p>Ported from the retired single-document path. Note the behavioural difference:
 * {@code BatchSigningCommandHandler} calls {@code cscAuthorizationPort.authorize(...)}
 * outside any try/catch, so a blank-SAD {@code SigningException} propagates to Camel's
 * {@code onException} and routes to the DLQ — it does <em>not</em> publish a FAILURE
 * reply the way the old single-doc handler did. We therefore assert on the guard itself
 * (signHash never called) and on the absence of a SUCCESS reply.
 */
class CscWireFormatIT extends IntegrationTestBase {

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
        stubCscAuthorizeWithSad("");  // blank SAD — production blank-SAD guard rejects this
        // No signHash stub is registered. It is never reached because the adapter throws
        // CSC_AUTH_EMPTY_SAD at the authorize step before signHash is ever invoked.
    }

    @Test
    void batchSigning_failsAtAuthorize_andNeverCallsSignHash_whenSadIsBlank() throws Exception {
        String docId = "doc-wire-" + UUID.randomUUID().toString().substring(0, 8);
        String sagaId = "saga-wire-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "corr-wire-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId = "batch-wire-" + UUID.randomUUID().toString().substring(0, 8);
        String originalKey = "XML/" + docId + "/orig.xml";

        minioHelper.putObject(originalKey,
                ("<Transcript><DocumentID>" + docId + "</DocumentID></Transcript>").getBytes());

        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_XML, correlationId, batchId,
                SignerRole.REGISTRAR, SigningFormat.XML,
                List.of(new BatchSigningCommand.Item(docId, "NUM-" + docId, originalKey)));

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        // Wait until the handler has reached authorize at least once. Camel will retry
        // (maximumRedeliveries(3)) and eventually DLQ, so the real count settles at 4 —
        // but do NOT assert 4. That would couple this test to the redelivery policy, which
        // has nothing to do with the wire-format guard being pinned here.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                wireMock.verify(moreThanOrExactly(1),
                        postRequestedFor(urlEqualTo("/csc/v2/credentials/authorize"))));

        // The invariant: authorize always throws on a blank SAD, so signHash is unreachable
        // at every instant — no race with in-flight redeliveries. This is the whole point of
        // the guard: never bill the HSM for a signature that can never be used.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/csc/v2/signatures/signHash")));

        var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                Duration.ofSeconds(2), sagaId);
        assertThat(reply)
                .as("no SUCCESS reply may be produced when the blank-SAD guard fires")
                .isEmpty();
    }
}
