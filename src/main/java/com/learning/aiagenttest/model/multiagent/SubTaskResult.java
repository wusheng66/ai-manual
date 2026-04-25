package com.learning.aiagenttest.model.multiagent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubTaskResult {
    private String subTaskId;
    private String taskDescription;
    private String agentName;
    private TaskType taskType;
    private int priority;
    private int sequenceNo;
    private boolean ordered;
    private boolean success;
    private String output;
}
