package com.wpanther.transcript.signing.integration;

import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the retirement of the single-document signing path. The orchestrator only ever
 * publishes to saga.command.transcript-signing.batch; the singular command topic and the
 * compensation topic have no producer anywhere. This test fails if either route is
 * resurrected.
 */
class SagaRouteTopologyIT extends IntegrationTestBase {

    @Autowired CamelContext camelContext;

    @Test
    void batchRouteIsPresent_andRetiredRoutesAreGone() {
        List<String> routeIds = camelContext.getRoutes().stream()
                .map(r -> r.getRouteId())
                .toList();

        assertThat(routeIds)
                .as("the batch signing route is the only saga command consumer")
                .contains("transcript-signing-batch-command");

        assertThat(routeIds)
                .as("the single-document signing route must stay retired")
                .doesNotContain("transcript-signing-command");

        assertThat(routeIds)
                .as("the compensation route must stay retired (it never had a producer)")
                .doesNotContain("transcript-signing-compensation");
    }
}
