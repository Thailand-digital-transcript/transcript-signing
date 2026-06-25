package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import com.wpanther.transcript.signing.integration.support.CscSignHashResponseTransformer;
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
 * Happy-path IT for batch signing: publish a single BatchSigningCommand with two items
 * (both XML, signerRole=REGISTRAR) and assert the saga:
 * <ol>
 *   <li>downloads both originals from MinIO,</li>
 *   <li>issues EXACTLY ONE multi-hash CSC signHash call with a 2-element {@code hash} array,</li>
 *   <li>embeds the per-item signatures deterministically,</li>
 *   <li>uploads the signed XMLs to MinIO at {@code XML/{batchId}/{documentId}/signed.xml},</li>
 *   <li>publishes a SUCCESS BatchSigningReplyEvent with two SIGNED ItemResults.</li>
 * </ol>
 *
 * <p>The single CSC call is the key correctness property of the multi-item batch: it
 * proves the items are not signed serially (one call per item). The per-item signature
 * verification on the resulting XMLs proves the multi-hash response was correctly
 * demultiplexed.
 */
class BatchSigningPipelineIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;
    @Autowired StorageProperties storageProperties;

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
    void batchSigningCommand_happyPath_signsAllItemsInOneCscCall() throws Exception {
        String docId1 = "doc-batch-1-d1-" + UUID.randomUUID().toString().substring(0, 8);
        String docId2 = "doc-batch-1-d2-" + UUID.randomUUID().toString().substring(0, 8);
        String originalKey1 = "XML/" + docId1 + "/orig.xml";
        String originalKey2 = "XML/" + docId2 + "/orig.xml";
        String batchId = "batch-1";
        String signedKey1 = String.format("XML/%s/%s/signed.xml", batchId, docId1);
        String signedKey2 = String.format("XML/%s/%s/signed.xml", batchId, docId2);
        String sagaId = "saga-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "corr-" + UUID.randomUUID().toString().substring(0, 8);

        // 1) Pre-upload the two original XMLs to MinIO. The batch handler downloads them
        //    by storageKey; without these the handler fails at Phase 5 before producing
        //    a reply.
        minioHelper.putObject(originalKey1,
                ("<Transcript><DocumentID>" + docId1 + "</DocumentID></Transcript>").getBytes());
        minioHelper.putObject(originalKey2,
                ("<Transcript><DocumentID>" + docId2 + "</DocumentID></Transcript>").getBytes());

        // 2) Build and publish the BatchSigningCommand. Null eventId/occurredAt/eventType/version
        //    on the parent IntegrationEvent are allowed (the IntegrationEvent base has
        //    protected no-arg + 4-arg ctors, and the @JsonIgnoreProperties-annotated ctor here
        //    is the public Jackson entry point).
        var items = List.of(
                new BatchSigningCommand.Item(docId1, "NUM-" + docId1, originalKey1),
                new BatchSigningCommand.Item(docId2, "NUM-" + docId2, originalKey2));
        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_XML, correlationId, batchId,
                SignerRole.REGISTRAR, SigningFormat.XML, items);

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        // 3) Await a SUCCESS BatchSigningReplyEvent on the reply topic for this saga.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), sagaId);
            assertThat(reply).isPresent();
            String value = reply.get().value();
            assertThat(value).contains("SUCCESS");
            assertThat(value).contains(batchId);
            assertThat(value).contains(docId1);
            assertThat(value).contains(docId2);
        });

        // 4) Assert CSC signHash was called EXACTLY once for this batch — the central
        //    "multi-item in one CSC call" property the batch design is supposed to
        //    guarantee. The transformer signs every hash in the request, so a 2-element
        //    request yields a 2-element response; the fact that both items end up SIGNED
        //    (asserted in step 3) proves the demultiplexing worked.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/csc/v2/signatures/signHash")));

        // 5) For each item, pull the signed XML and assert Santuario's XMLSignature
        //    verifies against the test certificate (which is the same key the WireMock
        //    CSC stub signs with). This is the same assertion the 1A IT uses.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(minioHelper.objectExists(signedKey1))
                    .as("Signed XML for d1 must be in MinIO").isTrue();
            assertThat(minioHelper.objectExists(signedKey2))
                    .as("Signed XML for d2 must be in MinIO").isTrue();
        });
        assertSignedXmlVerifies(minioHelper.getObjectBytes(signedKey1), cscCert());
        assertSignedXmlVerifies(minioHelper.getObjectBytes(signedKey2), cscCert());
    }

    /**
     * Parses the signed XML and asserts the embedded {@code ds:Signature} verifies
     * against {@code cert.getPublicKey()}. This validates BOTH the document reference
     * and the SignedProperties reference (Santuario's {@code checkSignatureValue}
     * re-digests every {@code ds:Reference} before validating the signature value).
     *
     * <p>W3C DOM does not preserve ID-type info across a serialize/parse round trip
     * without a DTD/Schema declaration, so we re-register the {@code Id} attributes
     * on the {@code ds:Signature} and {@code xades:SignedProperties} elements before
     * invoking the verifier. A conformant XAdES verifier does the same on receipt.
     */
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

    private static void registerIdAttribute(org.w3c.dom.Document doc, String namespaceUri, String localName) {
        var nodes = doc.getElementsByTagNameNS(namespaceUri, localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element el = (org.w3c.dom.Element) nodes.item(i);
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
        wireMock.stubFor(post(urlEqualTo("/csc/v2/credentials/info"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"cert\":{\"certificates\":[\"" + TEST_CERT_DER_BASE64
                                + "\"]},\"key\":{\"algo\":[\"1.2.840.113549.1.1.11\"],\"len\":2048}}")));
    }

    private void stubCscAuthorize() {
        wireMock.stubFor(post(urlEqualTo("/csc/v2/credentials/authorize"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"SAD\":\"sad-token-batch\",\"expiresIn\":60}")));
    }

    /**
     * The stub wires in {@link CscSignHashResponseTransformer} via
     * {@code .withTransformers(NAME)} — every request is signed inline with the test RSA
     * private key, so the resulting {@code ds:SignatureValue} verifies against the same
     * certificate the credentials/info stub returns. The transformer is multi-hash: a
     * 2-element request yields a 2-element response, with order preserved.
     */
    private void stubCscSignHash() {
        wireMock.stubFor(post(urlEqualTo("/csc/v2/signatures/signHash"))
                .withRequestBody(matchingJsonPath("$.SAD", matching("\\S+")))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"placeholder\"]}")
                        .withTransformers(CscSignHashResponseTransformer.NAME)));
    }
}
