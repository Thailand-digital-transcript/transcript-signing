package com.wpanther.transcript.signing.testsupport;

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/** Test-only: a self-signed cert + PEM helper standing in for a CSC credential certificate. */
public final class XmlSigTestKeys {
    private XmlSigTestKeys() {}

    public static X509Certificate selfSignedCert(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        var subject = new X500Principal("CN=Test Registrar, O=Test University, C=TH");
        var builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now), new Date(now - 86_400_000L),
                new Date(now + 365L * 86_400_000L), subject, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    public static String toPem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----";
    }
}
