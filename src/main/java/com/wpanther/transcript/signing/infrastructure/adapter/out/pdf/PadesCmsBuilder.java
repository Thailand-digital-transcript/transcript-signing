package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import com.wpanther.transcript.signing.domain.model.SigningException;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.*;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
public class PadesCmsBuilder {

    // encodedSignedAttrs is the DER-encoded SET built by PadesDigestComputer.
    // CSC signed SHA-256(encodedSignedAttrs), so we must embed exactly these bytes in the SignerInfo.
    public byte[] buildCmsSignature(byte[] encodedSignedAttrs, String rawSignatureBase64,
                                     String certificatePem) {
        try {
            X509Certificate cert = parseCertificate(certificatePem);
            byte[] rawSigBytes = Base64.getDecoder().decode(rawSignatureBase64);

            // Parse back to AttributeTable so BouncyCastle embeds them verbatim
            ASN1Set asn1Set = (ASN1Set) ASN1Primitive.fromByteArray(encodedSignedAttrs);
            AttributeTable signedAttrTable = new AttributeTable(asn1Set);

            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addCertificates(new JcaCertStore(List.of(cert)));

            X509CertificateHolder certHolder = new X509CertificateHolder(cert.getEncoded());
            ContentSigner precomputedSigner = new PrecomputedContentSigner(cert, rawSigBytes);
            SignerInfoGenerator signerInfoGenerator =
                    new SignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                            .setSignedAttributeGenerator(params -> signedAttrTable)
                            .build(precomputedSigner, certHolder);
            generator.addSignerInfoGenerator(signerInfoGenerator);

            CMSSignedData signedData = generator.generate(new CMSAbsentContent(), false);
            return signedData.getEncoded();
        } catch (Exception e) {
            throw new SigningException("PADES_CMS_BUILD_FAILED", "CMS signature build failed", e);
        }
    }

    private X509Certificate parseCertificate(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
                        .replace("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s+", "");
        byte[] certBytes = Base64.getDecoder().decode(b64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
    }

    private record PrecomputedContentSigner(X509Certificate cert, byte[] rawSignature)
            implements ContentSigner {

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            String algo = cert.getPublicKey().getAlgorithm();
            if ("EC".equals(algo)) {
                return new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.840.10045.4.3.2"));
            }
            return new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public byte[] getSignature() {
            return rawSignature;
        }
    }
}
