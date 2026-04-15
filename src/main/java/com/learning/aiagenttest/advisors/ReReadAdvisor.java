package com.learning.aiagenttest.advisors;

import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;

public class ReReadAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        AdvisedRequest befored = this.before(advisedRequest);
        return chain.nextAroundCall(befored);
    }

    private AdvisedRequest before(AdvisedRequest advisedRequest) {
        HashMap<String, Object> userParams = new HashMap<>(advisedRequest.userParams());
        userParams.put("re2_input_query", advisedRequest.userText());
        return AdvisedRequest.from(advisedRequest).userText("""
                {re2_input_query}
                Read the question again: {re2_input_query}
                """)
                .userParams(userParams).build();

    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(this.before(advisedRequest));
    }

    @Override
    public String getName() {
        return "re-read-advisor";
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
