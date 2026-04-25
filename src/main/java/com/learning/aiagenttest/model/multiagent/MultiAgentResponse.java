package com.learning.aiagenttest.model.multiagent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MultiAgentResponse {
    private String userIntent;
    private boolean decompositionEnabled;
    private int subTaskCount;
    private String summary;
    private List<SubTask> subTasks;
    private List<SubTaskResult> subTaskResults;
}
