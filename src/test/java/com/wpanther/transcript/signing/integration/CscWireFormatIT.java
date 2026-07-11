package com.wpanther.transcript.signing.integration;

import com.wpanther.transcript.signing.application.port.out.CscAuthorizationPort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the CSC authorize wire format against a real HTTP round-trip: WireMock → Feign →
 * Jackson → {@code CscAuthorizeResponse}.
 *
 * <p>The eidasremotesigning CSC returns the SAD under the <em>uppercase</em> key
 * {@code "SAD"}. Jackson is case-sensitive, so {@code CscAuthorizeResponse.sad} carries an
 * explicit {@code @JsonProperty("SAD")}. Drop that annotation and — because the class is
 * also {@code @JsonIgnoreProperties(ignoreUnknown = true)} — the key is silently ignored,
 * {@code sad} stays null, and every live authorize fails with {@code CSC_AUTH_EMPTY_SAD}.
 *
 * <p>{@link #authorize_readsTheUppercaseSadKey()} is the test that actually catches that
 * regression: it stubs a well-formed uppercase response and asserts the SAD is read back.
 * Asserting only the blank-SAD rejection would be worthless here — removing
 * {@code @JsonProperty("SAD")} <em>produces</em> a blank SAD, so a test that expects the
 * blank-SAD guard to fire would keep passing after the very regression it exists to catch.
 *
 * <p>This drives {@link CscAuthorizationPort} directly rather than publishing a Kafka
 * command. A blank SAD makes the batch handler throw, and Camel then redelivers it
 * ({@code maximumRedeliveries(3)}) with backoff. Those redeliveries outlive the test
 * method: {@code @AfterEach} swaps this class's blank-SAD stub for the next IT's valid one,
 * the persisted PENDING {@code BatchSigningItem} keeps the command replayable, and the
 * leftover redelivery then sails through authorize and bills a real {@code signHash} inside
 * the next test. It also fills the {@code csc-authorization} circuit-breaker window (10
 * failures) and trips it OPEN for 60s. Going straight at the port keeps the blast radius to
 * this class.
 */
class CscWireFormatIT extends IntegrationTestBase {

    @Autowired CscAuthorizationPort cscAuthorizationPort;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        stubCscOAuth2Token();
        stubCscCredentialInfo();
        // No signHash stub is registered: authorize must be the only endpoint reached.
    }

    @Test
    void authorize_readsTheUppercaseSadKey() {
        stubCscAuthorizeWithSad("sad-token-abc");

        String sad = cscAuthorizationPort.authorize("cred-a", List.of("hash-1", "hash-2"), "1111");

        assertThat(sad)
                .as("the SAD arrives under the uppercase JSON key \"SAD\"; losing "
                        + "@JsonProperty(\"SAD\") deserialises it to null")
                .isEqualTo("sad-token-abc");
    }

    @Test
    void authorize_blankSad_isRejectedBeforeSignHashIsEverCalled() {
        stubCscAuthorizeWithSad("");

        assertThatThrownBy(() ->
                cscAuthorizationPort.authorize("cred-a", List.of("hash-1"), "1111"))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("SAD");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/csc/v2/signatures/signHash")));
    }
}
