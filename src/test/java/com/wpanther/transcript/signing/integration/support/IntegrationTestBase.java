package com.wpanther.transcript.signing.integration.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    public static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    protected static WireMockServer wireMock;

    /**
     * Base64-encoded DER X.509 certificate (no PEM headers) generated once for all ITs.
     * PadesCmsBuilder.parseCertificate() and the XAdES signer (SantuarioXadesSigner) both
     * accept raw DER base64.
     */
    protected static String TEST_CERT_DER_BASE64;

    /**
     * Convenience accessor for the X509Certificate behind {@link #TEST_CERT_DER_BASE64}.
     * Tests that need to verify signatures produced by the WireMock CSC stub use this
     * so they can call {@code cscCert.getPublicKey()} on the same key the stub signs with.
     */
    protected static X509Certificate cscCert() {
        X509Certificate cert = CscSignHashResponseTransformer.getTestCertificate();
        if (cert == null) {
            throw new IllegalStateException(
                    "CSC test certificate not initialised — IntegrationTestBase.@BeforeAll did not run");
        }
        return cert;
    }

    static final String BUCKET = "signed-transcripts";

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        KAFKA.start();
        MINIO.start();
        // WireMock is shared across all test classes (Spring context is cached with its
        // port). Only create it once; subsequent @BeforeAll calls reuse the running instance.
        if (wireMock == null || !wireMock.isRunning()) {
            wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig()
                    .dynamicPort()
                    .extensions(new CscSignHashResponseTransformer.Impl()));
            wireMock.start();
        }
        if (TEST_CERT_DER_BASE64 == null) {
            TEST_CERT_DER_BASE64 = generateSelfSignedCertBase64();
        }
        createMinioBucket();
    }

    private static String generateSelfSignedCertBase64() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            X500Name subject = new X500Name("CN=TestCA");
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.ONE,
                    new Date(System.currentTimeMillis() - 60_000),
                    new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000),
                    subject, spki);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(kp.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(holder);
            // Hand the same key + cert to the WireMock signHash transformer so it can sign
            // the digest with the matching private key — without this the IT's
            // assertSignedXmlVerifies() would assert against the wrong public key.
            CscSignHashResponseTransformer.setTestKeyMaterial(kp, cert);
            return Base64.getEncoder().encodeToString(cert.getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test certificate", e);
        }
    }

    private static void createMinioBucket() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build()) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // containers are shared across test classes — bucket already exists
            }
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("camel.component.kafka.brokers", KAFKA::getBootstrapServers);
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.access-key", () -> "minioadmin");
        registry.add("app.storage.secret-key", () -> "minioadmin");
        registry.add("app.csc.service-url",
                () -> "http://localhost:" + wireMock.port());
        registry.add("app.csc.oauth2.token-url",
                () -> "http://localhost:" + wireMock.port() + "/oauth2/token");
    }

    @AfterEach
    void resetWireMock() {
        // Per transcript-signing/CLAUDE.md "IT HTTP isolation" rule: reset
        // both mappings AND the request journal so verify(...) counts from
        // one IT don't bleed into the next.
        wireMock.resetAll();
        wireMock.resetRequests();
    }

    protected void stubCscOAuth2Token() {
        wireMock.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-token\",\"expires_in\":3600}")));
    }

    protected void stubCscCredentialInfo() {
        wireMock.stubFor(post(urlEqualTo("/csc/v2/credentials/info"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"cert\":{\"certificates\":[\"" + TEST_CERT_DER_BASE64
                                + "\"]},\"key\":{\"algo\":[\"1.2.840.113549.1.1.11\"],\"len\":2048}}")));
    }

    protected void stubCscAuthorize() {
        stubCscAuthorizeWithSad("sad-token-test");
    }

    protected void stubCscAuthorizeWithSad(String sadValue) {
        wireMock.stubFor(post(urlEqualTo("/csc/v2/credentials/authorize"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"SAD\":\"" + sadValue + "\",\"expiresIn\":60}")));
    }

    protected void stubCscSignHash() {
        wireMock.stubFor(post(urlEqualTo("/csc/v2/signatures/signHash"))
                .withRequestBody(matchingJsonPath("$.SAD", matching("\\S+")))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"placeholder\"]}")
                        .withTransformers(CscSignHashResponseTransformer.NAME)));
    }

    protected void stubCscSignHashFakeSig() {
        String fakeSig = Base64.getEncoder().encodeToString(new byte[256]);
        wireMock.stubFor(post(urlEqualTo("/csc/v2/signatures/signHash"))
                .withRequestBody(matchingJsonPath("$.SAD", matching("\\S+")))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"signatures\":[\"" + fakeSig + "\"]}")));
    }
}
