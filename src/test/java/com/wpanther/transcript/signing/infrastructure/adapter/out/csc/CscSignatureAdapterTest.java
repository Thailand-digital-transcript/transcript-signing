package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.application.dto.CscSignatureResult;
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
    void signHash_successResponse_returnsSignatureWithTransactionId() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of("base64sig=="));
        response.setResponseID("txn-12345");
        when(signHashClient.signHash(any())).thenReturn(response);

        CscSignatureResult result = adapter.signHash("hash==", "sad-token", "cred-001", "2.16.840.1.101.3.4.2.1");

        assertThat(result.transactionId()).isEqualTo("txn-12345");
        assertThat(result.getSingleSignature()).isEqualTo("base64sig==");
    }

    @Test
    void signHash_successResponseWithBlankResponseId_generatesFallbackUuid() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of("base64sig=="));
        response.setResponseID("");
        when(signHashClient.signHash(any())).thenReturn(response);

        CscSignatureResult result = adapter.signHash("hash==", "sad-token", "cred-001", "oid");

        assertThat(result.transactionId()).isNotNull();
        assertThat(result.transactionId()).isNotBlank();
        assertThat(result.getSingleSignature()).isEqualTo("base64sig==");
    }

    @Test
    void signHash_successResponseWithNullResponseId_generatesFallbackUuid() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of("base64sig=="));
        response.setResponseID(null);
        when(signHashClient.signHash(any())).thenReturn(response);

        CscSignatureResult result = adapter.signHash("hash==", "sad-token", "cred-001", "oid");

        assertThat(result.transactionId()).isNotNull();
        assertThat(result.transactionId()).isNotBlank();
        assertThat(result.getSingleSignature()).isEqualTo("base64sig==");
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
    void signHashBatch_returnsAllSignaturesWithTransactionId() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of("SIG1", "SIG2", "SIG3"));
        response.setResponseID("batch-txn-789");
        when(signHashClient.signHash(any())).thenReturn(response);

        CscSignatureResult result = adapter.signHash(
                List.of("H1", "H2", "H3"), "SAD", "cred-1", "2.16.840.1.101.3.4.2.1");

        assertThat(result.transactionId()).isEqualTo("batch-txn-789");
        assertThat(result.signatures()).containsExactly("SIG1", "SIG2", "SIG3");
    }

    @Test
    void signHashBatch_countMismatch_throwsSigningException() {
        var response = new CscSignHashResponse();
        response.setSignatures(List.of("SIG1"));
        when(signHashClient.signHash(any())).thenReturn(response);

        assertThatThrownBy(() -> adapter.signHash(
                List.of("H1", "H2", "H3"), "SAD", "cred-1", "oid"))
                .isInstanceOf(SigningException.class)
                .satisfies(ex -> assertThat(((SigningException) ex).getErrorCode())
                        .isEqualTo("CSC_SIGN_COUNT_MISMATCH"));
    }
}
