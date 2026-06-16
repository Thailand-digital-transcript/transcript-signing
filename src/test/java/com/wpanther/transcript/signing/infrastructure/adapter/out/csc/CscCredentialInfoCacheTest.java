package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoRequest;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class CscCredentialInfoCacheTest {

    final CscCredentialInfoClient client = mock(CscCredentialInfoClient.class);
    final CscCredentialInfoCache cache = new CscCredentialInfoCache(
            client, new com.wpanther.transcript.signing.infrastructure.config.properties.CscProperties());

    private CscCredentialInfoResponse certResponse(String cert) {
        var resp = new CscCredentialInfoResponse();
        var c = new CscCredentialInfoResponse.Cert();
        c.setCertificates(List.of(cert));
        resp.setCert(c);
        return resp;
    }

    @Test
    void getCertificate_cachesPerCredentialId() {
        // Note: argThat returns null, so Mockito's matchers are re-evaluated against the prior
        // stub's recorded invocation (whose actual argument is null) when the second when() is
        // set up. The null guards below keep the lambdas robust against that re-check; the
        // lambda's actual matching against a real (non-null) request still works as expected.
        when(client.getCredentialInfo(argThat(r -> r != null && "cred-a".equals(r.getCredentialID()))))
                .thenReturn(certResponse("CERT_A"));
        when(client.getCredentialInfo(argThat(r -> r != null && "cred-b".equals(r.getCredentialID()))))
                .thenReturn(certResponse("CERT_B"));

        assertThat(cache.getCertificate("cred-a")).isEqualTo("CERT_A");
        assertThat(cache.getCertificate("cred-b")).isEqualTo("CERT_B");
        assertThat(cache.getCertificate("cred-a")).isEqualTo("CERT_A");   // cached

        verify(client, times(1)).getCredentialInfo(argThat(r -> r != null && "cred-a".equals(r.getCredentialID())));
    }
}
