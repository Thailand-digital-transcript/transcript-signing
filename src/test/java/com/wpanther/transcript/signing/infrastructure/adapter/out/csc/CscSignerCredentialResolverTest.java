package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.application.port.out.SignerCredentialResolver.ResolvedSigner;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CscSignerCredentialResolverTest {

    final CscCredentialInfoCache cache = mock(CscCredentialInfoCache.class);
    final CscProperties props = new CscProperties();
    final CscSignerCredentialResolver resolver = new CscSignerCredentialResolver(props, cache);

    @Test
    void resolve_combinesCredentialPinCertAndOid() {
        props.setHashAlgorithmOid("2.16.840.1.101.3.4.2.1");
        CscProperties.Credential seal = new CscProperties.Credential();
        seal.setCredentialId("cred-seal"); seal.setPin("9999");
        props.getCredentials().put(SignerRole.SEAL, seal);
        when(cache.getCertificate("cred-seal")).thenReturn("CERT_SEAL");

        ResolvedSigner r = resolver.resolve(SignerRole.SEAL);

        assertThat(r.credentialId()).isEqualTo("cred-seal");
        assertThat(r.pin()).isEqualTo("9999");
        assertThat(r.certificatePem()).isEqualTo("CERT_SEAL");
        assertThat(r.hashAlgorithmOid()).isEqualTo("2.16.840.1.101.3.4.2.1");
    }
}
