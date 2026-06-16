package com.wpanther.transcript.signing.infrastructure.adapter.out.xml;

import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.port.out.XadesPreparePort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import org.apache.xml.security.Init;
import org.apache.xml.security.algorithms.MessageDigestAlgorithm;   // ADDED for Santuario 4.0.4 (replaces Constants.ALGO_ID_DIGEST_SHA256)
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

/**
 * Apache Santuario implementation of two-pass remote XAdES-BASELINE-B signing. The document
 * reference (enveloped + C14N) AND the SignedProperties reference are both digested with the real
 * transforms, so a conformant verifier validates the whole document. The bytes signed by CSC are
 * SHA-256(C14N(SignedInfo)).
 */
@Component
public class SantuarioXadesSigner implements XadesPreparePort {

    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String C14N = Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS;

    static { Init.init(); }

    @Override
    public XadesPreparation prepare(byte[] xmlBytes, String certificatePem,
                                    Instant signingTime, String sigId) {
        try {
            X509Certificate cert = toCertificate(certificatePem);
            Document doc = buildSignatureSkeleton(xmlBytes, cert, signingTime, sigId);
            byte[] c14nSignedInfo = canonicalSignedInfoOf(doc);
            String digest = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(c14nSignedInfo));
            return new XadesPreparation(digest, serialize(doc));
        } catch (Exception e) {
            throw new SigningException("XADES_PREPARE_FAILED", "XAdES prepare failed", e);
        }
    }

    @Override
    public byte[] embed(byte[] xmlBytes, String certificatePem, Instant signingTime,
                        String sigId, String signatureValueBase64) {
        try {
            X509Certificate cert = toCertificate(certificatePem);
            Document doc = buildSignatureSkeleton(xmlBytes, cert, signingTime, sigId);
            Element sv = (Element) doc.getElementsByTagNameNS(
                    Constants.SignatureSpecNS, "SignatureValue").item(0);
            sv.setTextContent(signatureValueBase64);
            return serialize(doc);
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("XADES_EMBED_FAILED", "XAdES embed failed", e);
        }
    }

    private Document buildSignatureSkeleton(byte[] xmlBytes, X509Certificate cert,
                                            Instant signingTime, String sigId) throws Exception {
        Document doc = parseSecure(xmlBytes);

        XMLSignature sig = new XMLSignature(doc, "",
                XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256, C14N);
        sig.setId(sigId);
        doc.getDocumentElement().appendChild(sig.getElement());

        String signedPropsId = sigId + "-SignedProperties";
        Element object = doc.createElementNS(Constants.SignatureSpecNS, "ds:Object");
        object.appendChild(buildQualifyingProperties(doc, sigId, signedPropsId, signingTime, cert));
        sig.getElement().appendChild(object);

        Transforms tr = new Transforms(doc);
        tr.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        tr.addTransform(C14N);
        sig.addDocument("", tr, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);  // Santuario 4.0.4 location

        Transforms trProps = new Transforms(doc);
        trProps.addTransform(C14N);
        sig.addDocument("#" + signedPropsId, trProps, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256,  // Santuario 4.0.4 location
                null, "http://uri.etsi.org/01903#SignedProperties");

        sig.addKeyInfo(cert);
        sig.getSignedInfo().generateDigestValues();
        return doc;
    }

    private Element buildQualifyingProperties(Document doc, String sigId, String signedPropsId,
                                              Instant signingTime, X509Certificate cert)
            throws Exception {
        Element qp = doc.createElementNS(XADES_NS, "xades:QualifyingProperties");
        qp.setAttributeNS(null, "Target", "#" + sigId);
        Element sp = doc.createElementNS(XADES_NS, "xades:SignedProperties");
        sp.setAttributeNS(null, "Id", signedPropsId);
        sp.setIdAttributeNS(null, "Id", true);
        Element ssp = doc.createElementNS(XADES_NS, "xades:SignedSignatureProperties");
        Element st = doc.createElementNS(XADES_NS, "xades:SigningTime");
        st.setTextContent(signingTime.toString());
        ssp.appendChild(st);
        ssp.appendChild(buildSigningCertificateV2(doc, cert));
        sp.appendChild(ssp);
        qp.appendChild(sp);
        return qp;
    }

    private Element buildSigningCertificateV2(Document doc, X509Certificate cert) throws Exception {
        Element scv2 = doc.createElementNS(XADES_NS, "xades:SigningCertificateV2");
        Element c = doc.createElementNS(XADES_NS, "xades:Cert");
        Element cd = doc.createElementNS(XADES_NS, "xades:CertDigest");
        Element dm = doc.createElementNS(Constants.SignatureSpecNS, "ds:DigestMethod");
        dm.setAttributeNS(null, "Algorithm", MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);  // Santuario 4.0.4 location
        Element dv = doc.createElementNS(Constants.SignatureSpecNS, "ds:DigestValue");
        dv.setTextContent(Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        cd.appendChild(dm);
        cd.appendChild(dv);
        c.appendChild(cd);
        scv2.appendChild(c);
        return scv2;
    }

    private byte[] canonicalSignedInfoOf(Document doc) throws Exception {
        Element sigEl = (Element) doc.getElementsByTagNameNS(
                Constants.SignatureSpecNS, "Signature").item(0);
        XMLSignature sig = new XMLSignature(sigEl, "");
        return sig.getSignedInfo().getCanonicalizedOctetStream();
    }

    public static byte[] canonicalSignedInfo(byte[] preparedDocumentXml) throws Exception {
        Init.init();
        Document doc = secureFactory().newDocumentBuilder()
                .parse(new ByteArrayInputStream(preparedDocumentXml));
        Element sigEl = (Element) doc.getElementsByTagNameNS(
                Constants.SignatureSpecNS, "Signature").item(0);
        return new XMLSignature(sigEl, "").getSignedInfo().getCanonicalizedOctetStream();
    }

    private static X509Certificate toCertificate(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "").replaceAll("\\s+", "");
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    private static Document parseSecure(byte[] xmlBytes) throws Exception {
        return secureFactory().newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }

    private static byte[] serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }
}
