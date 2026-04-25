package com.learning.aiagenttest.model.multiagent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MultiAgentStreamEvent {
    private String traceId;
    private String eventType;
    private int index;
    private String taskId;
    private String taskDescription;
    private Integer sequenceNo;
    private boolean ordered;
    private String intentType;
    private String agentName;
    private String status;
    private String cacheLayer;
    private String content;
}
