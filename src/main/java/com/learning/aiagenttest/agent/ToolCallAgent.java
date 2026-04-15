package com.learning.aiagenttest.agent;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.learning.aiagenttest.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存了工具调用信息的响应
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用内置的工具调用机制，自己维护上下文
    private final ChatOptions chatOptions;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withProxyToolCalls(true)
                .build();
    }

    @Override
    public boolean think() {
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }

        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, chatOptions);
        try {
            // 构建 chatClient并调用
            ChatResponse chatResponse = getClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .tools(availableTools)
                    .call()
                    .chatResponse();

            // 处理结果
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            String result = assistantMessage.getText();

            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String toolCallInfo = toolCallList.stream().map(toolCall ->
                            String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining(","));

            log.info(toolCallInfo);
            // 记录结果
            if (toolCallList.isEmpty()) {
                getMessageList().add(assistantMessage);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error(getName() + "处理时异常： " + e.getMessage(), e);
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            return false;
        }
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }

        // 工具调用
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 记录消息
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage message = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // 返回结果
        String results = message.getResponses().stream().map(res -> {
            return "工具：" + res.name() + "完成了它的任务！结果：" + res.responseData();
        }).collect(Collectors.joining("\n"));
        log.info(results);
        if (getMessageList().stream().anyMatch(m -> m.getText().equals(message.getText()))) {
            setState(AgentState.FINISHED);
        }

        // 判读是否终止
        if (message.getResponses().stream().allMatch(e -> "doTerminate".equals(e.name()))) {
            setState(AgentState.FINISHED);
        }

        return results;
    }
}

