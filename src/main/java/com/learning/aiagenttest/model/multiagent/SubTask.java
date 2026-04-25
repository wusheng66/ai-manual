package com.learning.aiagenttest.model.multiagent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SubTask {
    private String id;
    private String description;
    private TaskType taskType;
    private int priority;
    /**
     * 是否是有逻辑顺序的任务。
     */
    private boolean ordered;
    /**
     * 有序任务序号，从 1 开始；无序任务可置为 0。
     */
    private int sequenceNo;
    /**
     * 当前任务依赖的前置任务 ID（有序任务可形成依赖链）。
     */
    private List<String> dependencyTaskIds;
}
