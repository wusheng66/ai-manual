package com.learning.aiagenttest.advisors;

import com.github.houbb.sensitive.word.core.SensitiveWordHelper;
import com.github.houbb.sensitive.word.support.replace.WordReplaceChar;
import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;

public class SensitiveWordAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        return chain.nextAroundCall(this.processSensitiveWord(advisedRequest));
    }

    private AdvisedRequest processSensitiveWord(AdvisedRequest advisedRequest) {
        AdvisedRequest.Builder modifyAdvisedRequest = AdvisedRequest.from(advisedRequest);
        String processedText = SensitiveWordHelper.replace(advisedRequest.userText(), new WordReplaceChar());
        modifyAdvisedRequest.userText(processedText);
        return modifyAdvisedRequest.build();
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(this.processSensitiveWord(advisedRequest));
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
