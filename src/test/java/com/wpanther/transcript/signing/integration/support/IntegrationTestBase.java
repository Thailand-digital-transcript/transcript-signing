package com.wpanther.transcript.signing.integration.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        KAFKA.start();
        MINIO.start();
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
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
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }
}
