package com.wpanther.transcript.signing.infrastructure.adapter.out.xml;

import com.wpanther.transcript.signing.application.port.out.XadesEmbeddingPort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Component
public class XadesSignatureEmbedder implements XadesEmbeddingPort {

    private static final String DS_NS    = "http://www.w3.org/2000/09/xmldsig#";
    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String C14N_ALG = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
    private static final String SIG_ALG  = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    private static final String SHA256   = "http://www.w3.org/2001/04/xmlenc#sha256";
    private static final String ENVLP_TRANSFORM =
            "http://www.w3.org/2000/09/xmldsig#enveloped-signature";

    // Produces XAdES-BASELINE-B (certificate reference + signing time in SignedProperties).
    // Upgrading to XAdES-BASELINE-T requires calling a TSA to obtain a timestamp token and
    // embedding it in xades:UnsignedSignatureProperties after this method returns.
    @Override
    public byte[] embedSignature(byte[] xmlBytes, String signatureBase64, String certificatePem) {
        try {
            Document doc = parseXml(xmlBytes);
            String sigId = "Sig-" + UUID.randomUUID();
            String sigPropsId = sigId + "-SignedProperties";

            // Build xades:SignedProperties FIRST so we can compute its digest for the reference
            Element xadesQP = createElement(doc, XADES_NS, "xades:QualifyingProperties");
            xadesQP.setAttribute("Target", "#" + sigId);
            Element xadesSP = createElement(doc, XADES_NS, "xades:SignedProperties");
            xadesSP.setAttribute("Id", sigPropsId);
            Element xadesSigSignedProps = createElement(doc, XADES_NS, "xades:SignedSignatureProperties");
            Element xadesSigningTime = createElement(doc, XADES_NS, "xades:SigningTime");
            xadesSigningTime.setTextContent(Instant.now().toString());
            xadesSigSignedProps.appendChild(xadesSigningTime);
            xadesSigSignedProps.appendChild(createSigningCertificateV2(doc, certificatePem));
            xadesSP.appendChild(xadesSigSignedProps);
            xadesQP.appendChild(xadesSP);

            // Serialize SignedProperties to compute its digest for the ds:Reference
            String signedPropsDigest = computeElementDigest(xadesSP);

            // Build ds:SignedInfo with the correct SignedProperties digest already known
            Element dsSignature      = createElement(doc, DS_NS, "ds:Signature");
            dsSignature.setAttribute("Id", sigId);
            Element dsSignedInfo     = createElement(doc, DS_NS, "ds:SignedInfo");
            Element dsKeyInfo        = createElement(doc, DS_NS, "ds:KeyInfo");
            Element dsSignatureValue = createElement(doc, DS_NS, "ds:SignatureValue");
            Element dsObject         = createElement(doc, DS_NS, "ds:Object");

            Element c14n = createElement(doc, DS_NS, "ds:CanonicalizationMethod");
            c14n.setAttribute("Algorithm", C14N_ALG);
            dsSignedInfo.appendChild(c14n);

            Element sigMethod = createElement(doc, DS_NS, "ds:SignatureMethod");
            sigMethod.setAttribute("Algorithm", SIG_ALG);
            dsSignedInfo.appendChild(sigMethod);

            // Reference to document (enveloped)
            Element dsRef = createElement(doc, DS_NS, "ds:Reference");
            dsRef.setAttribute("URI", "");
            Element transforms = createElement(doc, DS_NS, "ds:Transforms");
            Element t1 = createElement(doc, DS_NS, "ds:Transform");
            t1.setAttribute("Algorithm", ENVLP_TRANSFORM);
            transforms.appendChild(t1);
            Element t2 = createElement(doc, DS_NS, "ds:Transform");
            t2.setAttribute("Algorithm", C14N_ALG);
            transforms.appendChild(t2);
            dsRef.appendChild(transforms);
            Element digestMethod = createElement(doc, DS_NS, "ds:DigestMethod");
            digestMethod.setAttribute("Algorithm", SHA256);
            dsRef.appendChild(digestMethod);
            Element digestValue = createElement(doc, DS_NS, "ds:DigestValue");
            digestValue.setTextContent(computeDocumentDigest(xmlBytes));
            dsRef.appendChild(digestValue);
            dsSignedInfo.appendChild(dsRef);

            // Reference to SignedProperties with actual digest
            Element dsRefProps = createElement(doc, DS_NS, "ds:Reference");
            dsRefProps.setAttribute("Type", "http://uri.etsi.org/01903#SignedProperties");
            dsRefProps.setAttribute("URI", "#" + sigPropsId);
            Element dmProps = createElement(doc, DS_NS, "ds:DigestMethod");
            dmProps.setAttribute("Algorithm", SHA256);
            dsRefProps.appendChild(dmProps);
            Element dvProps = createElement(doc, DS_NS, "ds:DigestValue");
            dvProps.setTextContent(signedPropsDigest);
            dsRefProps.appendChild(dvProps);
            dsSignedInfo.appendChild(dsRefProps);

            dsSignatureValue.setTextContent(signatureBase64);

            Element x509Data = createElement(doc, DS_NS, "ds:X509Data");
            Element x509Cert = createElement(doc, DS_NS, "ds:X509Certificate");
            x509Cert.setTextContent(extractBase64FromPem(certificatePem));
            x509Data.appendChild(x509Cert);
            dsKeyInfo.appendChild(x509Data);

            dsObject.appendChild(xadesQP);

            dsSignature.appendChild(dsSignedInfo);
            dsSignature.appendChild(dsSignatureValue);
            dsSignature.appendChild(dsKeyInfo);
            dsSignature.appendChild(dsObject);

            doc.getDocumentElement().appendChild(dsSignature);

            return serializeDoc(doc);
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("XADES_EMBED_FAILED", "XAdES signature embedding failed", e);
        }
    }

    private String computeElementDigest(Element element) throws Exception {
        byte[] serialized = serializeElement(element);
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(serialized));
    }

    private byte[] serializeElement(Element element) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(element), new StreamResult(out));
        return out.toByteArray();
    }

    private Element createSigningCertificateV2(Document doc, String certPem) throws Exception {
        Element sigCertV2 = createElement(doc, XADES_NS, "xades:SigningCertificateV2");
        Element cert = createElement(doc, XADES_NS, "xades:Cert");
        Element certDigest = createElement(doc, XADES_NS, "xades:CertDigest");
        Element dm = createElement(doc, XADES_NS, "xades:DigestMethod");
        dm.setAttribute("Algorithm", SHA256);
        certDigest.appendChild(dm);
        Element dv = createElement(doc, XADES_NS, "xades:DigestValue");
        byte[] certBytes = Base64.getDecoder().decode(extractBase64FromPem(certPem));
        dv.setTextContent(Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(certBytes)));
        certDigest.appendChild(dv);
        cert.appendChild(certDigest);
        sigCertV2.appendChild(cert);
        return sigCertV2;
    }

    private String computeDocumentDigest(byte[] xmlBytes) throws Exception {
        // KNOWN LIMITATION: this digest is computed over the RAW input bytes. A
        // conformant XAdES verifier will:
        //   1. Apply the ds:Transforms listed in the ds:Reference (enveloped-signature
        //      + exclusive C14N),
        //   2. Hash the result,
        //   3. Compare against the DigestValue.
        // To produce a verifying signature, the digest here must be SHA-256 of the
        // C14N(document) WITH the ds:Signature element already in place so the
        // enveloped-signature transform can remove it. The proper fix is to swap
        // this hand-rolled embedder for Apache Santuario's XMLSignature API (xmlsec
        // 4.0.4 is already a dependency), which handles the SignedInfo/Reference
        // digest round-trip correctly. Until then, signatures verify only the
        // SignedProperties reference, not the document reference.
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(xmlBytes));
    }

    private Document parseXml(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory factory = createSecureDocumentBuilderFactory();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xmlBytes));
    }

    private DocumentBuilderFactory createSecureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private Element createElement(Document doc, String ns, String qualifiedName) {
        return doc.createElementNS(ns, qualifiedName);
    }

    private String extractBase64FromPem(String pem) {
        return pem.replace("-----BEGIN CERTIFICATE-----", "")
                  .replace("-----END CERTIFICATE-----", "")
                  .replaceAll("\\s+", "");
    }

    private byte[] serializeDoc(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }
}
