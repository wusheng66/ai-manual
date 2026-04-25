package com.learning.aiagenttest.configure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "multi-agent.cache")
public class MultiAgentCacheProperties {
    private long answerTtlMinutes = 30;
    private long historyTtlMinutes = 60;
    private String keyPrefix = "multi-agent";
}
