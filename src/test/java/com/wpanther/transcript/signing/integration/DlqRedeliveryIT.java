package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Camel route (not just {@code S3StorageAdapter.download} in isolation,
 * which is all {@code StorageAllowListTest} covers) to pin the redelivery behavior that
 * {@code SagaRouteConfig} declares: a disallowed source bucket
 * ({@code STORAGE_BUCKET_NOT_ALLOWED}) is a PERMANENT failure — {@code maximumRedeliveries(0)}
 * — while an ordinary storage fault ({@code STORAGE_DOWNLOAD_FAILED}, e.g. a missing key in an
 * otherwise-allowed bucket) is TRANSIENT and gets 3 retries with backoff before landing on the
 * DLQ. A regression that swapped these (or merged them under one non-retried/always-retried
 * clause) would not change either final outcome — both still end up on the DLQ — so only the
 * elapsed wall-clock time between publish and DLQ arrival distinguishes them.
 *
 * <p>{@code BatchSigningCommandHandler}'s Phase 4 (fresh-signature prep) calls
 * {@code downloadSource} uninstrumented by any try/catch, so on a batch's very first delivery
 * (every item starts PENDING, i.e. "needs a fresh signature") a download failure propagates
 * all the way out of {@code doHandle} to the Camel route — which is what lets these commands
 * reach {@code onException} instead of being swallowed as a per-item failure (that only
 * happens in the later embed loop, Phase 5).
 *
 * <p>{@code doHandle} resolves the signer's credential ({@code CscSignerCredentialResolver},
 * which round-trips CSC's OAuth2 token + credential-info endpoints) BEFORE the download loop
 * runs. Both cases here need that call to succeed so the failure under test is actually the
 * storage one, not an unrelated WireMock 404 — hence the CSC stubs in {@code @BeforeEach}.
 */
class DlqRedeliveryIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;

    KafkaTestHelper kafkaHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        wireMock.resetAll();
        stubCscOAuth2Token();
        stubCscCredentialInfo();
        // No signHash stub: neither case here should ever reach it — the storage
        // failure must happen first, in the download loop that precedes the CSC sign call.
    }

    @Test
    void permanentFailure_disallowedSourceBucket_dlqsOnTheFirstAttempt_noRedeliveryDelay()
            throws Exception {
        String sagaId = "saga-dlq-perm-" + UUID.randomUUID().toString().substring(0, 8);

        var item = new BatchSigningCommand.Item("doc-1", "90993829998",
                "2026/07/10/01/transcript-90993829998.registrar.xml",
                "keycloak-secrets",  // NOT in app.storage.allowed-source-buckets
                "2026/07/10/01/transcript-90993829998.dean.xml");
        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_XML, "corr-" + sagaId, "batch-" + sagaId,
                SignerRole.DEAN, SigningFormat.XML, List.of(item));

        Instant sentAt = Instant.now();
        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        var record = kafkaHelper.pollFor(topics.getDlq(), Duration.ofSeconds(20), sagaId);
        Duration elapsed = Duration.between(sentAt, Instant.now());

        assertThat(record)
                .as("a disallowed source bucket must still be dead-lettered")
                .isPresent();
        assertThat(record.get().value())
                .as("the disallowed bucket is the reason for the failure")
                .contains("keycloak-secrets");
        // The general onException(Exception.class) clause alone would cost at least
        // 1000ms (first redeliveryDelay) before even a SECOND attempt, and this
        // permanent-failure clause must never reach it. 5s comfortably covers real
        // Kafka/consumer-poll latency while remaining far below that 1000ms+ floor.
        assertThat(elapsed)
                .as("STORAGE_BUCKET_NOT_ALLOWED must be dead-lettered without any "
                        + "redelivery wait — maximumRedeliveries(0)")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void transientFailure_storageDownloadFailed_retriesWithBackoffBeforeDlq() throws Exception {
        String sagaId = "saga-dlq-transient-" + UUID.randomUUID().toString().substring(0, 8);

        // "signed-transcripts" IS in the allow list and exists in MinIO (created by
        // IntegrationTestBase), so this clears the allow-list check and fails later, inside
        // the actual S3 GetObject call, with a plain S3Exception -> STORAGE_DOWNLOAD_FAILED.
        var item = new BatchSigningCommand.Item("doc-2", "90993829999",
                "no/such/object-" + UUID.randomUUID() + ".xml",
                "signed-transcripts",
                "2026/07/10/01/transcript-90993829999.dean.xml");
        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_XML, "corr-" + sagaId, "batch-" + sagaId,
                SignerRole.DEAN, SigningFormat.XML, List.of(item));

        Instant sentAt = Instant.now();
        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        // 3 redeliveries with delays 1000ms, 3000ms, 9000ms (backOffMultiplier 3, capped at
        // maximumRedeliveryDelay 10000ms) put a ~13s floor under the DLQ arrival; give it
        // margin for the 4 processing attempts themselves plus Kafka round-trips.
        var record = kafkaHelper.pollFor(topics.getDlq(), Duration.ofSeconds(45), sagaId);
        Duration elapsed = Duration.between(sentAt, Instant.now());

        assertThat(record)
                .as("an ordinary storage fault must eventually be dead-lettered too")
                .isPresent();
        // Comfortably above the permanent-failure test's 5s ceiling, and below the
        // theoretical 1000+3000+9000=13000ms floor only to absorb clock/thread jitter.
        assertThat(elapsed)
                .as("STORAGE_DOWNLOAD_FAILED must burn through the 3 backed-off redeliveries "
                        + "before reaching the DLQ — this is what distinguishes it from the "
                        + "permanent-failure path above")
                .isGreaterThan(Duration.ofSeconds(10));
    }
}
