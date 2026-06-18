package com.wpanther.transcript.signing.infrastructure.adapter.out.csc;

import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscSignHashRequest;
import com.wpanther.transcript.signing.infrastructure.adapter.out.csc.dto.CscSignHashResponse;
import com.wpanther.transcript.signing.infrastructure.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "csc-sign-hash",
             url = "${app.csc.service-url}",
             configuration = FeignClientConfig.class)
public interface CscSignHashClient {

    @PostMapping("/csc/v2/signatures/signHash")
    CscSignHashResponse signHash(@RequestBody CscSignHashRequest request);
}
