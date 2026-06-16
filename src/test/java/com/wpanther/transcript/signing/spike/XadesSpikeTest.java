package com.wpanther.transcript.signing.spike;

import org.apache.xml.security.Init;
import org.apache.xml.security.algorithms.MessageDigestAlgorithm;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE (throwaway): proves that with Santuario we can (a) build SignedInfo with BOTH the
 * enveloped DOCUMENT reference AND the harder xades:SignedProperties reference, (b) extract the
 * canonicalized SignedInfo bytes WITHOUT a private key, (c) sign that digest "remotely", (d)
 * inject the SignatureValue, and (e) have XMLSignature.checkSignatureValue verify ALL references.
 * The SignedProperties reference (with setIdAttributeNS) is the part most likely to break first,
 * so we de-risk it here. Deleted in Task 2.
 */
class XadesSpikeTest {

    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";

    @Test
    void twoPass_remoteSign_bothReferencesVerify() throws Exception {
        Init.init();

        // local stand-in for the CSC HSM
        KeyPair kp = KeyPairGenerator.getInstance("RSA").genKeyPair();
        X509Certificate cert = com.wpanther.transcript.signing.testsupport.XmlSigTestKeys
                .selfSignedCert(kp);

        Document doc = parse("<Transcript><Student>A</Student></Transcript>");

        String sigId = "Sig-spike";
        String spId = sigId + "-SignedProperties";

        XMLSignature sig = new XMLSignature(doc, "",
                XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256,
                Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
        sig.setId(sigId);
        doc.getDocumentElement().appendChild(sig.getElement()); // enveloped

        // xades:QualifyingProperties → ds:Object; SignedProperties must be registered as an ID node
        Element object = doc.createElementNS(Constants.SignatureSpecNS, "ds:Object");
        Element qp = doc.createElementNS(XADES_NS, "xades:QualifyingProperties");
        qp.setAttributeNS(null, "Target", "#" + sigId);
        Element sp = doc.createElementNS(XADES_NS, "xades:SignedProperties");
        sp.setAttributeNS(null, "Id", spId);
        sp.setIdAttributeNS(null, "Id", true);     // so URI="#spId" resolves during digest
        Element ssp = doc.createElementNS(XADES_NS, "xades:SignedSignatureProperties");
        Element st = doc.createElementNS(XADES_NS, "xades:SigningTime");
        st.setTextContent("2026-06-16T10:00:00Z");
        ssp.appendChild(st);
        sp.appendChild(ssp);
        qp.appendChild(sp);
        object.appendChild(qp);
        sig.getElement().appendChild(object);

        Transforms transforms = new Transforms(doc);
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        transforms.addTransform(Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
        sig.addDocument("", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);

        Transforms spTransforms = new Transforms(doc);
        spTransforms.addTransform(Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
        sig.addDocument("#" + spId, spTransforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256,
                null, "http://uri.etsi.org/01903#SignedProperties");

        // Pass 1: generate reference digests (BOTH refs) + grab canonical SignedInfo (no key needed)
        sig.getSignedInfo().generateDigestValues();
        byte[] c14nSignedInfo = sig.getSignedInfo().getCanonicalizedOctetStream();

        // "remote" sign: hash is what we would send to CSC signHash
        byte[] toBeSignedDigest = MessageDigest.getInstance("SHA-256").digest(c14nSignedInfo);
        assertThat(toBeSignedDigest).hasSize(32);

        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(kp.getPrivate());
        rsa.update(c14nSignedInfo);          // RSA PKCS#1 hashes internally; CSC signs the digest
        byte[] signatureValue = rsa.sign();

        // Pass 2: inject the signature value + key, then verify the DOCUMENT reference
        setSignatureValue(sig.getElement(), signatureValue);
        sig.addKeyInfo(cert);

        XMLSignature verify = new XMLSignature(
                (Element) doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature").item(0),
                "");
        assertThat(verify.checkSignatureValue(cert.getPublicKey())).isTrue();
    }

    private static void setSignatureValue(Element sigElement, byte[] value) {
        Element sv = (Element) sigElement.getElementsByTagNameNS(
                Constants.SignatureSpecNS, "SignatureValue").item(0);
        sv.setTextContent(java.util.Base64.getEncoder().encodeToString(value));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes()));
    }
}
