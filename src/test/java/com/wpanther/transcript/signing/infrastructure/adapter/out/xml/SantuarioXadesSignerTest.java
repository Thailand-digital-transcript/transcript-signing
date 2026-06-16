package com.wpanther.transcript.signing.infrastructure.adapter.out.xml;

import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.testsupport.XmlSigTestKeys;
import org.apache.xml.security.Init;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.utils.Constants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class SantuarioXadesSignerTest {

    static KeyPair kp;
    static X509Certificate cert;
    static String certPem;
    final SantuarioXadesSigner signer = new SantuarioXadesSigner();

    @BeforeAll
    static void keys() throws Exception {
        Init.init();
        kp = KeyPairGenerator.getInstance("RSA").genKeyPair();
        cert = XmlSigTestKeys.selfSignedCert(kp);
        certPem = XmlSigTestKeys.toPem(cert);
    }

    private byte[] sampleXml() {
        return "<Transcript><Student>Somchai</Student></Transcript>".getBytes(StandardCharsets.UTF_8);
    }

    /** "CSC": sign the canonical SignedInfo the signer prepared. We reproduce it by re-preparing. */
    private String remoteSign(byte[] xml, Instant t, String sigId) throws Exception {
        XadesPreparation prep = signer.prepare(xml, certPem, t, sigId);
        byte[] c14nSignedInfo = SantuarioXadesSigner.canonicalSignedInfo(prep.preparedDocumentXml());
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(kp.getPrivate());
        rsa.update(c14nSignedInfo);
        return Base64.getEncoder().encodeToString(rsa.sign());
    }

    @Test
    void embed_documentAndPropsReferences_verify() throws Exception {
        byte[] xml = sampleXml();
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        String sigId = "Sig-fixed-1";

        String signatureValue = remoteSign(xml, t, sigId);
        byte[] signed = signer.embed(xml, certPem, t, sigId, signatureValue);

        Document doc = parse(signed);
        Element sigEl = (Element) doc.getElementsByTagNameNS(
                Constants.SignatureSpecNS, "Signature").item(0);
        XMLSignature verify = new XMLSignature(sigEl, "");
        assertThat(verify.checkSignatureValue(cert.getPublicKey())).isTrue();
    }

    @Test
    void prepare_isDeterministic_forSameInputs() {
        byte[] xml = sampleXml();
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        XadesPreparation a = signer.prepare(xml, certPem, t, "Sig-x");
        XadesPreparation b = signer.prepare(xml, certPem, t, "Sig-x");
        assertThat(a.signedInfoDigestBase64()).isEqualTo(b.signedInfoDigestBase64());
        assertThat(a.preparedDocumentXml()).isEqualTo(b.preparedDocumentXml());
    }

    @Test
    void embed_signedProperties_carriesSigningTimeAndCertDigest() throws Exception {
        byte[] xml = sampleXml();
        Instant t = Instant.parse("2026-06-16T10:00:00Z");
        String sv = remoteSign(xml, t, "Sig-q");
        byte[] signed = signer.embed(xml, certPem, t, "Sig-q", sv);
        Document doc = parse(signed);

        NodeList qp = doc.getElementsByTagNameNS(
                "http://uri.etsi.org/01903/v1.3.2#", "QualifyingProperties");
        assertThat(qp.getLength()).isGreaterThanOrEqualTo(1);

        String signingTime = doc.getElementsByTagNameNS(
                "http://uri.etsi.org/01903/v1.3.2#", "SigningTime").item(0).getTextContent();
        assertThat(signingTime).isEqualTo("2026-06-16T10:00:00Z");

        String certDigest = doc.getElementsByTagNameNS(
                "http://uri.etsi.org/01903/v1.3.2#", "CertDigest").item(0)
                .getTextContent().replaceAll("\\s+", "");
        String expected = Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        assertThat(certDigest).contains(expected);
    }

    @Test
    void prepare_rejectsXxe_withSigningException() {
        byte[] evil = ("<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "
                + "\"file:///etc/passwd\">]><root>&xxe;</root>").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> signer.prepare(evil, certPem, Instant.now(), "Sig-z"))
                .isInstanceOf(com.wpanther.transcript.signing.domain.model.SigningException.class);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        // W3C DOM does not preserve ID-type info across serialize/parse without a DTD/Schema.
        // Santuario's fragment-URI resolver needs the Id attribute marked as ID-type on the
        // re-parsed DOM. Conformant XAdES verifiers register IDs the same way on receipt.
        NodeList sigs = doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature");
        for (int i = 0; i < sigs.getLength(); i++) {
            Element sigEl = (Element) sigs.item(i);
            if (sigEl.hasAttributeNS(null, "Id")) {
                sigEl.setIdAttributeNS(null, "Id", true);
            }
        }
        NodeList sps = doc.getElementsByTagNameNS(
                "http://uri.etsi.org/01903/v1.3.2#", "SignedProperties");
        for (int i = 0; i < sps.getLength(); i++) {
            Element sp = (Element) sps.item(i);
            if (sp.hasAttributeNS(null, "Id")) {
                sp.setIdAttributeNS(null, "Id", true);
            }
        }
        return doc;
    }
}
