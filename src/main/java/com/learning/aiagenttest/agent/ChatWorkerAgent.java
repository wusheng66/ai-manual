package com.learning.aiagenttest.agent;

import com.learning.aiagenttest.model.multiagent.SubTask;
import com.learning.aiagenttest.model.multiagent.SubTaskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

@Slf4j
public class ChatWorkerAgent extends QueuedAsyncAgent {

    private final ChatModel chatModel;
    private final ToolCallback[] toolCallbacks;

    public ChatWorkerAgent(String agentName, ChatModel chatModel, ToolCallback[] toolCallbacks) {
        super(agentName);
        this.chatModel = chatModel;
        this.toolCallbacks = toolCallbacks;
    }

    @Override
    protected SubTaskResult doExecute(SubTask subTask, String originalRequest) {
        String rolePrompt = buildRolePrompt(subTask);
        String taskPrompt = """
                原始需求：%s
                当前子任务：%s

                约束：
                1. 只回答本子任务；
                2. 输出简洁明确；
                3. 必要时给出操作步骤。
                """.formatted(originalRequest, subTask.getDescription());

        String output = ChatClient.builder(chatModel)
                .defaultTools(toolCallbacks)
                .build()
                .prompt()
                .system(rolePrompt)
                .user(taskPrompt)
                .call()
                .content();

        return SubTaskResult.builder()
                .subTaskId(subTask.getId())
                .taskDescription(subTask.getDescription())
                .agentName(getAgentName())
                .taskType(subTask.getTaskType())
                .priority(subTask.getPriority())
                .ordered(subTask.isOrdered())
                .sequenceNo(subTask.getSequenceNo())
                .success(true)
                .output(output == null ? "" : output)
                .build();
    }

    private String buildRolePrompt(SubTask subTask) {
        return getAgentName() + "：你是多 Agent 协作中的专职执行者，当前任务类型="
                + subTask.getTaskType() + "，请专注完成分配的子任务。";
    }
}
