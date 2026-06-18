package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoRequest;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscCredentialInfoResponse;
import com.wpanther.transcript.signing.infrastructure.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "csc-credential-info",
             url = "${app.csc.service-url}",
             configuration = FeignClientConfig.class)
public interface CscCredentialInfoClient {

    @PostMapping("/csc/v2/credentials/info")
    CscCredentialInfoResponse getCredentialInfo(@RequestBody CscCredentialInfoRequest request);
}
