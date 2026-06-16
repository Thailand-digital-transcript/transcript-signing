package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscSignHashResponse;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CscSignatureAdapterTest {

    @Mock CscSignHashClient signHashClient;

    @InjectMocks CscSignatureAdapter adapter;

    @Test
    void signHash_successResponse_returnsFirstSignature() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of("base64sig=="));
        when(signHashClient.signHash(any())).thenReturn(response);

        String result = adapter.signHash("hash==", "sad-token", "cred-001", "2.16.840.1.101.3.4.2.1");

        assertThat(result).isEqualTo("base64sig==");
    }

    @Test
    void signHash_emptySignatures_throwsSigningException() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of());
        when(signHashClient.signHash(any())).thenReturn(response);

        assertThatThrownBy(() -> adapter.signHash("hash==", "sad-token", "cred-001", "oid"))
                .isInstanceOf(SigningException.class);
    }

    @Test
    void signHash_feignException_throwsSigningException() {
        when(signHashClient.signHash(any())).thenThrow(FeignException.class);

        assertThatThrownBy(() -> adapter.signHash("hash==", "sad-token", "cred-001", "oid"))
                .isInstanceOf(SigningException.class);
    }

    @Test
    void signHashBatch_returnsAllSignaturesInOrder() {
        var response = new com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscSignHashResponse();
        response.setSignatures(java.util.List.of("SIG1", "SIG2", "SIG3"));
        org.mockito.Mockito.when(signHashClient.signHash(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);

        java.util.List<String> sigs = adapter.signHash(
                java.util.List.of("H1", "H2", "H3"), "SAD", "cred-1", "2.16.840.1.101.3.4.2.1");

        org.assertj.core.api.Assertions.assertThat(sigs).containsExactly("SIG1", "SIG2", "SIG3");
    }
}
