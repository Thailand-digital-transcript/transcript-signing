package com.wpanther.transcript.signing.infrastructure.adapter.out.xml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class XadesSignatureEmbedderTest {

    XadesSignatureEmbedder embedder;
    String fakeCert;
    String fakeSignature;

    @BeforeEach
    void setUp() {
        embedder = new XadesSignatureEmbedder();
        fakeCert = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getEncoder().encodeToString(new byte[512])
                + "\n-----END CERTIFICATE-----";
        fakeSignature = Base64.getEncoder().encodeToString("fakesig".getBytes());
    }

    @Test
    void embedSignature_producesXmlWithDsSignatureElement() throws Exception {
        byte[] xml = readSample("/samples/transcript.xml");
        byte[] signed = embedder.embedSignature(xml, fakeSignature, fakeCert);
        Document doc = parseXml(signed);
        NodeList sigElements = doc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature");
        assertThat(sigElements.getLength()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void embedSignature_producesXadesQualifyingProperties() throws Exception {
        byte[] xml = readSample("/samples/transcript.xml");
        byte[] signed = embedder.embedSignature(xml, fakeSignature, fakeCert);
        Document doc = parseXml(signed);
        NodeList xadesNodes = doc.getElementsByTagNameNS(
                "http://uri.etsi.org/01903/v1.3.2#", "QualifyingProperties");
        assertThat(xadesNodes.getLength()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void embedSignature_xxeProtection_refusesExternalEntity() {
        byte[] maliciousXml = ("<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<root>&xxe;</root>").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> embedder.embedSignature(maliciousXml, fakeSignature, fakeCert))
                .isInstanceOf(Exception.class);
    }

    private byte[] readSample(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assert in != null;
            return in.readAllBytes();
        }
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
}
