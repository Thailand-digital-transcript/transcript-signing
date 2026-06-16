package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TranscriptSigningPipelineIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;
    @Autowired StorageProperties storageProperties;

    KafkaTestHelper kafkaHelper;
    MinioTestHelper minioHelper;

    @BeforeEach
    void setUpKafka() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
        minioHelper = new MinioTestHelper(
                MINIO.getS3URL(), "minioadmin", "minioadmin",
                storageProperties.getBucketName());
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
            var reply = kafkaHelper.pollFor(topics.getSagaReplyTranscriptSigning(),
                    Duration.ofSeconds(2), "saga-it-001");
            assertThat(reply).isPresent();
            assertThat(reply.get().value()).contains("SUCCESS");
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var event = kafkaHelper.pollFor(topics.getTranscriptSigned(),
                    Duration.ofSeconds(2), "doc-it-001");
            assertThat(event).isPresent();
            assertThat(event.get().value()).contains("doc-it-001");
        });

        // The signed XML is uploaded by the saga at the end of the happy path — pull it
        // back and assert Santuario's XMLSignature.checkSignatureValue succeeds for BOTH
        // the document reference and the SignedProperties reference, against the public
        // key whose matching private key the WireMock CSC stub signed with.
        String signedKey = "XML/doc-it-001/attempt-0/signed.xml";
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(minioHelper.objectExists(signedKey))
                    .as("Signed XML must be present in MinIO before we can verify it")
                    .isTrue();
        });
        byte[] signedXml = minioHelper.getObjectBytes(signedKey);
        assertSignedXmlVerifies(signedXml, cscCert());
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

    /**
     * The stub wires in {@link CscSignHashResponseTransformer} via
     * {@code .withTransformers(NAME)} — every request is signed inline with the test RSA
     * private key, so the resulting {@code ds:SignatureValue} verifies against the same
     * certificate the credentials/info stub returns.
     */
    private void stubCscSignHash() {
        wireMock.stubFor(post(urlEqualTo("/csc/v1/signatures/signHash"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"placeholder\"]}")
                        .withTransformers(CscSignHashResponseTransformer.NAME)));
    }

    private String fakeCertBase64() {
        return TEST_CERT_DER_BASE64;
    }
}
