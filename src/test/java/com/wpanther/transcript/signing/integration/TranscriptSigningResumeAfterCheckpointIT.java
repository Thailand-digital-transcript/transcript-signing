package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.port.out.XadesPreparePort;
import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocument;
import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocumentId;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.domain.model.SigningStatus;
import com.wpanther.transcript.signing.domain.repository.SignedTranscriptDocumentRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the crash-and-resume guarantee of the XAdES two-pass design: a command whose row is
 * already at the TX1.5 checkpoint (signature obtained from CSC, {@code sigId}/{@code signingTime}
 * persisted) but not yet embedded must, on (re)delivery, take the short-circuit — no fresh CSC
 * call — and embed deterministically using the persisted params, producing a verifying signature.
 *
 * <p><b>Why a pre-seeded checkpoint instead of injecting a Phase-4b failure:</b> failure injection
 * requires replacing the storage bean (e.g. {@code @SpyBean}), which forks a SECOND Spring context.
 * That second context starts its own Camel consumer in the shared group
 * {@code transcript-signing-group}, so the command is load-balanced between two consumers and is
 * often handled by the other (non-spy) context — the failure never fires in the full IT suite.
 * This test instead reproduces the exact post-checkpoint precondition directly in the DB and adds
 * NO context customizers, so it shares the single cached context like every other IT (one consumer,
 * no competition). The strong assertion (embedded {@code SigningTime}/{@code @Id} equal the
 * persisted values) still proves deterministic resume with no drift.
 */
class TranscriptSigningResumeAfterCheckpointIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;
    @Autowired StorageProperties storageProperties;
    @Autowired SignedTranscriptDocumentRepository repository;
    @Autowired XadesPreparePort xadesPreparePort;

    KafkaTestHelper kafkaHelper;
    MinioTestHelper minioHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        minioHelper = new MinioTestHelper(
                MINIO.getS3URL(), "minioadmin", "minioadmin",
                storageProperties.getBucketName());
        // No CSC stubs needed: the TX1.5 short-circuit skips authorize/signHash entirely.
    }

    @Test
    void preSeededCheckpoint_redelivery_embedsWithPersistedParamsAndVerifies() throws Exception {
        String documentId = "doc-resume-" + UUID.randomUUID();
        String documentNumber = "TH-RESUME-001";
        String sagaId = "saga-resume-" + UUID.randomUUID();
        String correlationId = "corr-resume-" + UUID.randomUUID();
        String xmlContent = "<Transcript><DocumentID>" + documentId + "</DocumentID></Transcript>";
        byte[] xmlBytes = xmlContent.getBytes(StandardCharsets.UTF_8);

        // Fixed, millisecond-precision params — exactly what TX1.5 would have persisted. Using a
        // ms-precision instant means it round-trips through TIMESTAMPTZ byte-for-byte (this is the
        // production truncatedTo(MILLIS) invariant; here we control it directly).
        Instant signingTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String sigId = "Sig-resume-" + UUID.randomUUID();

        // Compute the pending signature the way the real path would: prepare → digest → CSC sign.
        XadesPreparation prep = xadesPreparePort.prepare(
                xmlBytes, TEST_CERT_DER_BASE64, signingTime, sigId);
        String pendingSignature = CscSignHashResponseTransformer.signDigest(prep.signedInfoDigestBase64());

        // Pre-seed the row in SIGNING with the TX1.5 checkpoint populated. version = null so the
        // repository performs an INSERT (Hibernate treats a null @Version as a new entity).
        SignedTranscriptDocument checkpointRow = SignedTranscriptDocument.rehydrate(
                SignedTranscriptDocumentId.generate(), documentId, documentNumber, SigningFormat.XML,
                "XML/" + documentId + "/attempt-0/original.xml",
                "http://unused/original.xml", xmlBytes.length,
                null, null, null,                              // signedDoc path/url/size
                "resume-tx-1", pendingSignature, TEST_CERT_DER_BASE64,
                null, null,                                    // signatureLevel, signatureTimestamp
                SigningStatus.SIGNING, null, 0,                // status, errorMessage, retryCount
                Instant.now().truncatedTo(ChronoUnit.MILLIS),  // createdAt
                null,                                          // completedAt
                null,                                          // version (null => INSERT)
                sigId, signingTime);                           // appended XAdES signing params
        repository.save(checkpointRow);

        // Deliver the command. The single shared context's consumer takes the TX1.5 short-circuit.
        sendCommand(sagaId, correlationId, documentId, documentNumber, xmlContent);

        // The redelivery must publish SUCCESS (only one reply exists for this unique sagaId).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var replies = kafkaHelper.pollForAll(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), sagaId);
            assertThat(replies.stream().anyMatch(r -> r.value().contains("SUCCESS")))
                    .as("delivery must publish a SUCCESS reply (saw " + replies.size() + " replies)")
                    .isTrue();
        });

        // retryCount stayed 0, so the signed key is attempt-0.
        String signedKey = "XML/" + documentId + "/attempt-0/signed.xml";
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(minioHelper.objectExists(signedKey))
                        .as("Signed XML must be present in MinIO after resume").isTrue());
        byte[] signedXml = minioHelper.getObjectBytes(signedKey);

        // Strong assertions: verifies AND the embedded params equal the persisted checkpoint values.
        assertSignedXmlVerifies(signedXml, cscCert());
        assertThat(extractSigningTime(signedXml))
                .as("Embedded xades:SigningTime must equal the persisted value (deterministic resume)")
                .isEqualTo(signingTime.toString());
        assertThat(extractSignatureId(signedXml))
                .as("Embedded ds:Signature/@Id must equal the persisted sigId (deterministic resume)")
                .isEqualTo(sigId);
    }

    private void sendCommand(String sagaId, String correlationId, String documentId,
                             String documentNumber, String xmlContent) throws Exception {
        var command = new ProcessTranscriptSigningCommand(
                null, null, null, null,
                sagaId, SagaStep.SIGN_XML, correlationId,
                documentId, documentNumber, SigningFormat.XML, xmlContent, null);
        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigning(), command);
    }

    private static String extractSigningTime(byte[] signedXml) throws Exception {
        var doc = parse(signedXml);
        var nodes = doc.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", "SigningTime");
        if (nodes.getLength() == 0) {
            throw new AssertionError("Signed XML has no xades:SigningTime element");
        }
        return nodes.item(0).getTextContent();
    }

    private static String extractSignatureId(byte[] signedXml) throws Exception {
        var doc = parse(signedXml);
        var sigEl = (org.w3c.dom.Element) doc.getElementsByTagNameNS(
                org.apache.xml.security.utils.Constants.SignatureSpecNS, "Signature").item(0);
        if (sigEl == null) {
            throw new AssertionError("Signed XML has no ds:Signature element");
        }
        return sigEl.getAttributeNS(null, "Id");
    }

    private static void assertSignedXmlVerifies(byte[] signedXml, java.security.cert.X509Certificate cert)
            throws Exception {
        org.apache.xml.security.Init.init();
        var doc = parse(signedXml);
        registerIdAttribute(doc, org.apache.xml.security.utils.Constants.SignatureSpecNS, "Signature");
        registerIdAttribute(doc, "http://uri.etsi.org/01903/v1.3.2#", "SignedProperties");
        var sigEl = (org.w3c.dom.Element) doc.getElementsByTagNameNS(
                org.apache.xml.security.utils.Constants.SignatureSpecNS, "Signature").item(0);
        var xmlSig = new org.apache.xml.security.signature.XMLSignature(sigEl, "");
        assertThat(xmlSig.checkSignatureValue(cert.getPublicKey()))
                .as("Resumed signature must verify against the CSC test key").isTrue();
    }

    private static org.w3c.dom.Document parse(byte[] xml) throws Exception {
        var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
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
}
