package com.wpanther.transcript.signing.infrastructure.config.properties;

import com.wpanther.transcript.signing.domain.model.SignerRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CscPropertiesTest {

    @Test
    void credentialForRole_returnsConfiguredCredential() {
        CscProperties props = new CscProperties();
        CscProperties.Credential dean = new CscProperties.Credential();
        dean.setCredentialId("cred-dean");
        dean.setPin("2222");
        props.getCredentials().put(SignerRole.DEAN, dean);

        CscProperties.Credential resolved = props.credentialForRole(SignerRole.DEAN);

        assertThat(resolved.getCredentialId()).isEqualTo("cred-dean");
        assertThat(resolved.getPin()).isEqualTo("2222");
    }

    @Test
    void credentialForRole_throwsWhenRoleUnconfigured() {
        CscProperties props = new CscProperties();
        assertThatThrownBy(() -> props.credentialForRole(SignerRole.SEAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEAL");
    }
}
