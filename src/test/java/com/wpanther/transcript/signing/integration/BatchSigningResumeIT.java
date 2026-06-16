package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.application.port.out.XadesPreparePort;
import com.wpanther.transcript.signing.domain.model.BatchItemStatus;
import com.wpanther.transcript.signing.domain.model.BatchJobStatus;
import com.wpanther.transcript.signing.domain.model.BatchSigningItem;
import com.wpanther.transcript.signing.domain.model.BatchSigningJob;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.domain.repository.BatchSigningJobRepository;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import com.wpanther.transcript.signing.integration.support.CscSignHashResponseTransformer;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import com.wpanther.transcript.signing.integration.support.MinioTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the item-level idempotency of BatchSigningCommandHandler: a job with one item already
 * SIGNED (signed XML in MinIO, row populated) and one item PENDING (no signature yet) re-signs
 * ONLY the pending item on redelivery. The pre-seed pattern (no @SpyBean, no context fork)
 * mirrors 1A Task 8 — the single shared context's consumer in {@code transcript-signing-group}
 * takes the command, and {@code itemsNeedingFreshSignature()} excludes the SIGNED item so
 * exactly one CSC signHash call is made (for the PENDING item only).
 */
class BatchSigningResumeIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;
    @Autowired StorageProperties storageProperties;
    @Autowired BatchSigningJobRepository batchRepository;
    @Autowired XadesPreparePort xadesPreparePort;

    KafkaTestHelper kafkaHelper;
    MinioTestHelper minioHelper;

    @BeforeEach
    void setUp() {
        // The base @AfterEach already calls wireMock.resetAll(), but defensively clear here
        // so the test never depends on test-class execution order.
        wireMock.resetAll();
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        minioHelper = new MinioTestHelper(
                MINIO.getS3URL(), "minioadmin", "minioadmin",
                storageProperties.getBucketName());
        stubCscCredentialInfo();
        stubCscOAuth2Token();
        stubCscAuthorize();
        stubCscSignHash();
    }

    @Test
    void preSeededJob_redelivery_signsOnlyPendingItemAndLeavesSignedItemUntouched() throws Exception {
        String docId1 = "doc-resume-d1-" + UUID.randomUUID().toString().substring(0, 8);   // will be SIGNED pre-seed
        String docId2 = "doc-resume-d2-" + UUID.randomUUID().toString().substring(0, 8);   // will be PENDING
        String batchId = "batch-resume-1";
        String sagaId = "saga-resume-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "corr-resume-" + UUID.randomUUID().toString().substring(0, 8);
        String originalKey1 = "XML/" + docId1 + "/orig.xml";
        String originalKey2 = "XML/" + docId2 + "/orig.xml";
        String signedKey1 = "XML/" + batchId + "/" + docId1 + "/signed.xml";
        String signedKey2 = "XML/" + batchId + "/" + docId2 + "/signed.xml";

        // Both originals uploaded so the handler can download them in the embed loop.
        byte[] originalXml1 = ("<Transcript><DocumentID>" + docId1 + "</DocumentID></Transcript>").getBytes(StandardCharsets.UTF_8);
        byte[] originalXml2 = ("<Transcript><DocumentID>" + docId2 + "</DocumentID></Transcript>").getBytes(StandardCharsets.UTF_8);
        minioHelper.putObject(originalKey1, originalXml1);
        minioHelper.putObject(originalKey2, originalXml2);

        // Compute the SIGNED item's pending signature + signed XML using the same path the
        // handler uses, so the pre-seeded stored signed XML verifies.
        Instant signingTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String sigId = "Sig-resume-" + UUID.randomUUID();
        XadesPreparation prep = xadesPreparePort.prepare(originalXml1, TEST_CERT_DER_BASE64, signingTime, sigId);
        String pendingSignature = CscSignHashResponseTransformer.signDigest(prep.signedInfoDigestBase64());
        byte[] signedXml1 = xadesPreparePort.embed(originalXml1, TEST_CERT_DER_BASE64, signingTime, sigId, pendingSignature);
        minioHelper.putObject(signedKey1, signedXml1);

        // Pre-seed a BatchSigningJob with one SIGNED item and one PENDING item. version = null
        // so JPA performs an INSERT. Note: BatchSigningItem.rehydrate's last two args are
        // (errorMessage, signedDocSize) — the SIGNED item is not in error, and its size is the
        // pre-uploaded signed XML length.
        BatchSigningItem signedItem = BatchSigningItem.rehydrate(
                UUID.randomUUID(), docId1, "NUM-" + docId1, originalKey1,
                BatchItemStatus.SIGNED, sigId, signingTime, pendingSignature,
                signedKey1, "http://unused/" + signedKey1, null, (long) signedXml1.length);
        BatchSigningItem pendingItem = BatchSigningItem.rehydrate(
                UUID.randomUUID(), docId2, "NUM-" + docId2, originalKey2,
                BatchItemStatus.PENDING, null, null, null,
                null, null, null, null);
        BatchSigningJob job = BatchSigningJob.rehydrate(
                UUID.randomUUID(), correlationId, batchId, sagaId,
                SignerRole.REGISTRAR, SigningFormat.XML, BatchJobStatus.SIGNING,
                List.of(signedItem, pendingItem), null);
        batchRepository.save(job);

        // Deliver the batch command. The single shared context's consumer takes it.
        var items = List.of(
                new BatchSigningCommand.Item(docId1, "NUM-" + docId1, originalKey1),
                new BatchSigningCommand.Item(docId2, "NUM-" + docId2, originalKey2));
        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_XML, correlationId, batchId,
                SignerRole.REGISTRAR, SigningFormat.XML, items);
        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        // The reply must be SUCCESS with both items SIGNED.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var replies = kafkaHelper.pollForAll(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), sagaId);
            assertThat(replies.stream().anyMatch(r -> r.value().contains("SUCCESS")))
                    .as("delivery must publish a SUCCESS reply (saw " + replies.size() + " replies)")
                    .isTrue();
            assertThat(replies.stream().anyMatch(r ->
                    r.value().contains(docId2) && r.value().contains("SIGNED")))
                    .as("PENDING item must be marked SIGNED in the reply").isTrue();
        });

        // CSC signHash was called exactly once: the PENDING item alone needed a fresh signature.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/csc/v1/signatures/signHash")));

        // The PENDING item now has a signed XML.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(minioHelper.objectExists(signedKey2))
                        .as("PENDING item's signed XML must be present in MinIO after resume").isTrue());
        assertSignedXmlVerifies(minioHelper.getObjectBytes(signedKey2), cscCert());

        // The SIGNED item's stored signed doc is unchanged: still present, still verifies.
        assertThat(minioHelper.objectExists(signedKey1))
                .as("Pre-seeded SIGNED item's signed XML must still be present").isTrue();
        assertSignedXmlVerifies(minioHelper.getObjectBytes(signedKey1), cscCert());
    }

    /** Copy of the 1A IT's helper — verifies the embedded ds:Signature against the test cert. */
    private void assertSignedXmlVerifies(byte[] signedXml, java.security.cert.X509Certificate cert)
            throws Exception {
        org.apache.xml.security.Init.init();
        var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        var doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(signedXml));
        var sigEl = (org.w3c.dom.Element) doc.getElementsByTagNameNS(
                org.apache.xml.security.utils.Constants.SignatureSpecNS, "Signature").item(0);
        assertThat(sigEl)
                .as("Signed XML must contain a ds:Signature element")
                .isNotNull();
        registerIdAttribute(doc, org.apache.xml.security.utils.Constants.SignatureSpecNS, "Signature");
        registerIdAttribute(doc, "http://uri.etsi.org/01903/v1.3.2#", "SignedProperties");
        var xmlSig = new org.apache.xml.security.signature.XMLSignature(sigEl, "");
        assertThat(xmlSig.checkSignatureValue(cert.getPublicKey()))
                .as("Embedded ds:Signature must verify against the CSC test certificate")
                .isTrue();
    }

    private static void registerIdAttribute(org.w3c.dom.Document doc, String ns, String localName) {
        var nodes = doc.getElementsByTagNameNS(ns, localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            var el = (org.w3c.dom.Element) nodes.item(i);
            if (el.hasAttributeNS(null, "Id")) {
                el.setIdAttributeNS(null, "Id", true);
            }
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
                        .withBody("{\"cert\":{\"certificates\":[\"" + TEST_CERT_DER_BASE64 + "\"]},\"key\":{\"algo\":\"RSA\"}}")));
    }

    private void stubCscAuthorize() {
        wireMock.stubFor(post(urlEqualTo("/csc/v1/credentials/authorize"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"SAD\":\"sad-token-resume\",\"expiresIn\":60}")));
    }

    private void stubCscSignHash() {
        wireMock.stubFor(post(urlEqualTo("/csc/v1/signatures/signHash"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"placeholder\"]}")
                        .withTransformers(CscSignHashResponseTransformer.NAME)));
    }
}
