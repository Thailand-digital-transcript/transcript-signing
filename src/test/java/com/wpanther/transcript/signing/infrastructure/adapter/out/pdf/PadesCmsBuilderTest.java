package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class PadesCmsBuilderTest {

    PadesCmsBuilder builder = new PadesCmsBuilder();

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void buildCms_withFakeSignature_returnsValidCmsStructure() throws Exception {
        // The original spec used 512 random bytes as a "fake certificate", but
        // PadesCmsBuilder uses CertificateFactory which requires a real
        // X.509 DER structure. We generate a real self-signed cert here so
        // the test exercises the production code path that feeds the
        // certificate into JcaCertStore.
        String realCert = generateSelfSignedCertPem();
        byte[] fakeRawSig = new byte[256];
        String fakeSigBase64 = Base64.getEncoder().encodeToString(fakeRawSig);

        // Build a minimal valid DER signedAttrs (what CSC signed the hash of)
        byte[] fakeSignedAttrs = buildMinimalSignedAttrs();

        byte[] cms = builder.buildCmsSignature(fakeSignedAttrs, fakeSigBase64, realCert);

        assertThat(cms).isNotEmpty();
        CMSSignedData signedData = new CMSSignedData(cms);
        assertThat(signedData.getSignerInfos().size()).isEqualTo(1);
    }

    private byte[] buildMinimalSignedAttrs() throws Exception {
        ASN1EncodableVector attrs = new ASN1EncodableVector();
        attrs.add(new Attribute(CMSAttributes.contentType,
                new DERSet(PKCSObjectIdentifiers.data)));
        attrs.add(new Attribute(CMSAttributes.messageDigest,
                new DERSet(new DEROctetString(new byte[32]))));
        return new DERSet(attrs).getEncoded("DER");
    }

    private String generateSelfSignedCertPem() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=PadesTest");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 60_000);
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, spki);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);

        String b64 = Base64.getEncoder().encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----";
    }
}
