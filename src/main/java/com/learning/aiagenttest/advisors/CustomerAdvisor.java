package com.learning.aiagenttest.advisors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;

import java.util.Optional;

@Slf4j
public class CustomerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        // 1. 处理请求（前置处理）
        AdvisedRequest modifiedRequest = processRequest(advisedRequest);

        // 2. 调用链中的下一个Advisor
        AdvisedResponse response = chain.nextAroundCall(modifiedRequest);

        // 3. 处理响应（后置处理）
        return processResponse(response);
    }

    private AdvisedResponse processResponse(AdvisedResponse advisedResponse) {
        Optional.ofNullable(advisedResponse.response()).ifPresent(adv -> log.info("chat response content: {}", adv.getResult().getOutput().getText()));
        return advisedResponse;
    }


    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        // 1. 处理请求
        AdvisedRequest modifiedRequest = processRequest(advisedRequest);

        // 2. 调用链中的下一个Advisor并处理流式响应
        return chain.nextAroundStream(modifiedRequest)
                .map(response -> processResponse(response));
    }

    private AdvisedRequest processRequest(AdvisedRequest advisedRequest) {
        log.info("chat request content: {}", advisedRequest.userText());
        return advisedRequest;
    }


    @Override
    public String getName() {
        return "customer-advisor";
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
