package com.wpanther.transcript.signing.integration;

import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-doc table is renamed, not dropped: rename satisfies ddl-auto=validate
 * (no entity maps it) while keeping any rows recoverable. Boot itself proves validate
 * passes — if an entity still mapped the old name, the context would fail to start.
 */
class RetiredTableMigrationIT extends IntegrationTestBase {

    @Autowired DataSource dataSource;

    private boolean tableExists(String name) throws Exception {
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    @Test
    void oldTableIsRenamed_notDropped() throws Exception {
        assertThat(tableExists("signed_transcript_documents"))
                .as("the single-doc table must no longer exist under its old name")
                .isFalse();
        assertThat(tableExists("signed_transcript_documents_retired"))
                .as("it must survive under the retired name, so rows stay recoverable")
                .isTrue();
    }

    @Test
    void batchTablesAreUntouched() throws Exception {
        assertThat(tableExists("batch_signing_jobs")).isTrue();
        assertThat(tableExists("batch_signing_items")).isTrue();
    }
}
