package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscAuthorizeResponse;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CscAuthorizationAdapterTest {

    @Mock CscAuthorizationClient authorizationClient;

    @InjectMocks CscAuthorizationAdapter adapter;

    @Test
    void authorize_successResponse_returnsSadToken() {
        var response = new CscAuthorizeResponse();
        response.setSad("sad-token-abc");
        when(authorizationClient.authorize(any())).thenReturn(response);

        String sad = adapter.authorize("cred-001", "hash==", "1234");

        assertThat(sad).isEqualTo("sad-token-abc");
    }

    @Test
    void authorize_emptySadToken_throwsSigningException() {
        var response = new CscAuthorizeResponse();
        response.setSad("");
        when(authorizationClient.authorize(any())).thenReturn(response);

        assertThatThrownBy(() -> adapter.authorize("cred-001", "hash==", null))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("SAD");
    }

    @Test
    void authorize_feignException_throwsSigningException() {
        when(authorizationClient.authorize(any())).thenThrow(FeignException.class);

        assertThatThrownBy(() -> adapter.authorize("cred-001", "hash==", null))
                .isInstanceOf(SigningException.class);
    }

    @Test
    void authorizeBatch_sendsAllHashesAndNumSignatures() {
        var resp = new CscAuthorizeResponse();
        resp.setSad("SAD-XYZ");
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscAuthorizeRequest.class);
        org.mockito.Mockito.when(authorizationClient.authorize(captor.capture())).thenReturn(resp);

        String sad = adapter.authorize("cred-1", java.util.List.of("H1", "H2"), "1234");

        org.assertj.core.api.Assertions.assertThat(sad).isEqualTo("SAD-XYZ");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getHash()).containsExactly("H1", "H2");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getNumSignatures()).isEqualTo(2);
    }
}
