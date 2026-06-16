package com.wpanther.transcript.signing.application.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchSigningCommandJsonTest {

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void deserializes_withItemsRoleAndFormat() throws Exception {
        String json = """
            {"sagaId":"s1","sagaStep":"sign-xml","correlationId":"c1",
             "batchId":"batch-1","signerRole":"REGISTRAR","format":"XML",
             "items":[{"documentId":"d1","documentNumber":"n1","storageKey":"XML/d1/orig.xml"},
                      {"documentId":"d2","documentNumber":"n2","storageKey":"XML/d2/orig.xml"}]}
            """;
        BatchSigningCommand cmd = mapper.readValue(json, BatchSigningCommand.class);

        assertThat(cmd.getBatchId()).isEqualTo("batch-1");
        assertThat(cmd.getSignerRole()).isEqualTo(SignerRole.REGISTRAR);
        assertThat(cmd.getFormat()).isEqualTo(SigningFormat.XML);
        assertThat(cmd.getItems()).hasSize(2);
        assertThat(cmd.getItems().get(0).getStorageKey()).isEqualTo("XML/d1/orig.xml");
    }
}
