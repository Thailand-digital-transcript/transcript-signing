package com.wpanther.transcript.signing.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Test-only stand-in for the CSC signHash endpoint. Wired into the WireMock server as a
 * per-stub transformer (see {@link #NAME}). For every signHash request, it parses the JSON
 * body, decodes every base64 SHA-256 digest in the {@code hash} array, reconstructs the
 * PKCS#1 v1.5 DigestInfo for SHA-256, and signs it with the test RSA private key using
 * {@code NONEwithRSA}. The matching certificate is exposed via {@link #getTestCertificate()}
 * so the IT can assert the embedded XML signature verifies.
 *
 * <p>A 1-element request yields a 1-element response, so the 1A single-hash ITs keep
 * working; multi-hash (batch) requests are signed element-by-element in order.
 *
 * <p>The test {@link KeyPair} / {@link X509Certificate} are written into volatile static
 * fields by {@link IntegrationTestBase#startContainers()}, which runs once per JVM. The
 * transformer is registered as a WireMock extension at server startup; it dereferences the
 * fields lazily on each request, so initialization order is not an issue.
 */
public final class CscSignHashResponseTransformer {

    /** Transformer name referenced by {@code .withTransformer(NAME)} on the stub. */
    public static final String NAME = "csc-sign-hash-transformer";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SIGN_HASH_PATH = "/csc/v1/signatures/signHash";

    private static volatile KeyPair testKeyPair;
    private static volatile X509Certificate testCertificate;

    private CscSignHashResponseTransformer() {}

    public static void setTestKeyMaterial(KeyPair keyPair, X509Certificate certificate) {
        testKeyPair = keyPair;
        testCertificate = certificate;
    }

    public static X509Certificate getTestCertificate() {
        return testCertificate;
    }

    /**
     * Produces the base64 CSC signature for a given base64 SHA-256 digest — identical to what the
     * signHash stub returns over the wire. Used by tests that pre-seed a TX1.5 checkpoint row so
     * the stored {@code pending_signature} matches what a real CSC call would have produced.
     */
    public static String signDigest(String hashBase64) throws Exception {
        return Base64.getEncoder().encodeToString(signDigestBase64(hashBase64));
    }

    /** Transformer implementation registered with WireMock via {@code extensions(...)}. */
    public static class Impl extends ResponseTransformer {

        @Override
        public Response transform(Request request, Response response,
                                   FileSource files, Parameters parameters) {
            if (!SIGN_HASH_PATH.equals(request.getUrl())) {
                return response;
            }
            try {
                String body = request.getBodyAsString();
                java.util.List<String> hashes = extractHashesBase64(body);
                StringBuilder sigs = new StringBuilder("[");
                for (int i = 0; i < hashes.size(); i++) {
                    byte[] sig = signDigestBase64(hashes.get(i));
                    if (i > 0) sigs.append(',');
                    sigs.append('"').append(Base64.getEncoder().encodeToString(sig)).append('"');
                }
                sigs.append(']');
                String responseJson = "{\"signatures\":" + sigs + "}";
                return Response.response()
                        .status(200)
                        .body(responseJson)
                        .headers(response.getHeaders())
                        .build();
            } catch (Exception e) {
                throw new RuntimeException("CscSignHashResponseTransformer failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String getName() {
            return NAME;
        }
    }

    private static java.util.List<String> extractHashesBase64(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode hashNode = root.get("hash");
        if (hashNode == null || !hashNode.isArray() || hashNode.isEmpty()) {
            throw new IllegalStateException("signHash request missing 'hash' array");
        }
        java.util.List<String> out = new java.util.ArrayList<>(hashNode.size());
        for (int i = 0; i < hashNode.size(); i++) {
            out.add(hashNode.get(i).asText());
        }
        return out;
    }

    /**
     * Reconstructs the PKCS#1 v1.5 DigestInfo and signs it with raw RSA. This matches what
     * a real CSC HSM would produce for signAlgo = 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
     * given a SHA-256 digest input — the wire format is the DER-encoded DigestInfo, not the
     * raw digest.
     */
    static byte[] signDigestBase64(String hashBase64) throws Exception {
        KeyPair kp = testKeyPair;
        if (kp == null) {
            throw new IllegalStateException("CscSignHashResponseTransformer: test key pair not initialised");
        }
        byte[] hash = Base64.getDecoder().decode(hashBase64);
        DigestInfo digestInfo = new DigestInfo(
                new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), hash);
        byte[] digestInfoEncoded = digestInfo.getEncoded();

        PrivateKey privateKey = kp.getPrivate();
        Signature rsa = Signature.getInstance("NONEwithRSA");
        rsa.initSign(privateKey);
        rsa.update(digestInfoEncoded);
        return rsa.sign();
    }
}
