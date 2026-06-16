package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.application.port.out.SignerCredentialResolver;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CscSignerCredentialResolver implements SignerCredentialResolver {

    private final CscProperties cscProperties;
    private final CscCredentialInfoCache credentialInfoCache;

    @Override
    public ResolvedSigner resolve(SignerRole role) {
        CscProperties.Credential c = cscProperties.credentialForRole(role);
        String cert = credentialInfoCache.getCertificate(c.getCredentialId());
        return new ResolvedSigner(c.getCredentialId(), c.getPin(), cert,
                cscProperties.getHashAlgorithmOid());
    }
}
