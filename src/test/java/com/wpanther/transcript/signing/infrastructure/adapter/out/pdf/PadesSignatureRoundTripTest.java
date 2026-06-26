package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end PAdES round trip exercising the real prepare → CMS-build → embed seam
 * with a local test key standing in for the CSC remote signer. Proves the embedded
 * signature actually verifies against the final PDF's signed byte range — i.e. the
 * digest the signer committed to matches the bytes that ship. This is what a real
 * PDF signature validator checks (and what catches a byte-range/digest mismatch
 * between {@link PadesDigestComputer} and {@link PadesEmbedder}).
 */
class PadesSignatureRoundTripTest {

    PadesSignatureAdapter adapter = new PadesSignatureAdapter(
            new PadesDigestComputer(), new PadesCmsBuilder(), new PadesEmbedder());

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void signedPdf_verifiesAgainstItsByteRange() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        X509Certificate cert = selfSigned(keyPair);
        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";

        byte[] pdf = minimalPdf();

        // Phase 1: prepare + hash the signedAttrs
        PadesDigestResult digest = adapter.computeByteRangeDigest(pdf);

        // Phase 2: CSC stand-in — RSA-sign the DER signedAttrs (yields PKCS#1 over
        // SHA-256(signedAttrs), exactly what the CMS SignerInfo signature must be).
        Signature rsa = Signature.getInstance("SHA256withRSA", BouncyCastleProvider.PROVIDER_NAME);
        rsa.initSign(keyPair.getPrivate());
        rsa.update(digest.encodedSignedAttrs());
        String rawSignatureBase64 = Base64.getEncoder().encodeToString(rsa.sign());

        // Phase 3: embed
        byte[] signedPdf = adapter.embedSignature(digest, rawSignatureBase64, certPem);

        // Validate: the CMS must verify against the PDF's actual signed byte range.
        try (PDDocument doc = Loader.loadPDF(signedPdf)) {
            PDSignature pd = doc.getSignatureDictionaries().get(0);
            byte[] signedContent = pd.getSignedContent(new ByteArrayInputStream(signedPdf));
            byte[] cmsBytes = pd.getContents(signedPdf);

            CMSSignedData cms = new CMSSignedData(
                    new CMSProcessableByteArray(signedContent), cmsBytes);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            X509CertificateHolder holder = (X509CertificateHolder)
                    cms.getCertificates().getMatches(signer.getSID()).iterator().next();

            assertThat(signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(holder)))
                    .as("Embedded PAdES signature must verify against the final PDF byte range")
                    .isTrue();
        }
    }

    private static byte[] minimalPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp) throws Exception {
        X500Name dn = new X500Name("CN=PadesRoundTrip");
        Date from = new Date(System.currentTimeMillis() - 60_000);
        Date to = new Date(System.currentTimeMillis() + 3_600_000);
        var builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(System.currentTimeMillis()), from, to, dn, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);
    }
}
