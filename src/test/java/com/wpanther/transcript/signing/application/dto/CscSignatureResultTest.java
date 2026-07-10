package com.wpanther.transcript.signing.application.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CscSignatureResultTest {

    @Test
    void getSingleSignature_returnsOnlyElementWhenOne() {
        CscSignatureResult result = new CscSignatureResult("txn-1", List.of("sig-1"));
        assertThat(result.getSingleSignature()).isEqualTo("sig-1");
        assertThat(result.transactionId()).isEqualTo("txn-1");
        assertThat(result.signatures()).containsExactly("sig-1");
    }

    @Test
    void getSingleSignature_throwsWhenListIsEmpty() {
        CscSignatureResult result = new CscSignatureResult("txn-1", List.of());
        assertThatThrownBy(result::getSingleSignature)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No signatures");
    }

    @Test
    void getSingleSignature_throwsWhenListIsNull() {
        CscSignatureResult result = new CscSignatureResult("txn-1", null);
        assertThatThrownBy(result::getSingleSignature)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No signatures");
    }

    @Test
    void getSingleSignature_throwsWhenListHasMoreThanOne() {
        CscSignatureResult result = new CscSignatureResult("txn-1", List.of("a", "b"));
        assertThatThrownBy(result::getSingleSignature)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected single signature")
                .hasMessageContaining("2");
    }
}
