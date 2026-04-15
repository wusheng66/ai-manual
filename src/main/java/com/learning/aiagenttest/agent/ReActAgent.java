package com.learning.aiagenttest.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class ReActAgent extends BaseAgent {


    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    /**
     * process current state and determine next step
     * @return should act
     */
    public abstract boolean think();

    /**
     * execute determined action
     * @return
     */
    public abstract String act();
    private int duplicateThreshold = 2;

    /**
     * 处理陷入循环的状态
     */
    protected void handleStuckState() {
        String stuckPrompt = "观察到重复响应。考虑新策略，避免重复已尝试过的无效路径。";
        this.setNextStepPrompt(stuckPrompt + "\n" + (this.getNextStepPrompt() != null ? this.getNextStepPrompt() : ""));
        System.out.println("Agent detected stuck state. Added prompt: " + stuckPrompt);
    }

    /**
     * 检查代理是否陷入循环
     *
     * @return 是否陷入循环
     */
    protected boolean isStuck() {
        List<Message> messages = this.getMessageList();
        if (messages.size() < 2) {
            return false;
        }

        Message lastMessage = messages.get(messages.size() - 1);
        if (lastMessage.getText() == null || lastMessage.getText().isEmpty()) {
            return false;
        }

        // 计算重复内容出现次数
        int duplicateCount = 0;
        for (int i = messages.size() - 2; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.getMessageType() == MessageType.ASSISTANT &&
                    lastMessage.getText().equals(msg.getText())) {
                duplicateCount++;
            }
        }

        return duplicateCount >= this.duplicateThreshold;
    }



    @Override
    public String step() {
        try {
            boolean shouldAct = this.think();
            if (!shouldAct) {
                return "Thinking finished - No action.";
            }
            String act = this.act();
            // 每一步 step 执行完都要检查是否陷入循环
            if (isStuck()) {
                handleStuckState();
            }
            return act;
        } catch (Exception e) {
            log.error("Execute error", e);
            return "Step executed error: " + e.getMessage();
        }
    }
}
