package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscSignHashRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CscSignHashRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sadField_serialisesAsUppercaseSAD() throws Exception {
        CscSignHashRequest request = new CscSignHashRequest();
        request.setSAD("test-sad-token");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.has("SAD"))
                .as("SAD must serialise with uppercase key 'SAD' (CSC v2 wire format)")
                .isTrue();
        assertThat(json.has("sad"))
                .as("lowercase 'sad' must not appear — would be silently ignored by eidasremotesigning")
                .isFalse();
        assertThat(json.get("SAD").asText()).isEqualTo("test-sad-token");
    }

    @Test
    void hashField_serialisesAsHashes() throws Exception {
        CscSignHashRequest request = new CscSignHashRequest();
        request.setHash(List.of("abc123"));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.has("hashes"))
                .as("hash list must serialise as 'hashes' (CSC v2 wire format)")
                .isTrue();
        assertThat(json.get("hashes").get(0).asText()).isEqualTo("abc123");
    }
}
