package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscAuthorizeRequest;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscAuthorizeResponse;
import com.wpanther.transcript.signing.infrastructure.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "csc-authorization",
             url = "${app.csc.service-url}",
             configuration = FeignClientConfig.class)
public interface CscAuthorizationClient {

    @PostMapping("/csc/v2/credentials/authorize")
    CscAuthorizeResponse authorize(@RequestBody CscAuthorizeRequest request);
}
