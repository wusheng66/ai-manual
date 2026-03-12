package com.learning.aiagenttest.agent;

import com.learning.aiagenttest.model.AgentState;
import opennlp.tools.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseAgent {


    private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);
    private String name;

    private String systemPrompt;

    private String nextStepPrompt;

    private AgentState state = AgentState.IDLE;

    private int currentStep = 0;

    private int maxStep = 100;

    private ChatClient client;

    private List<Message> messageList = new ArrayList<>();

    public String run(String userPrompt) {
        // 1、base check
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Can not run agent State: " + this.state);
        }
        if (StringUtil.isEmpty(userPrompt)) {
            throw new RuntimeException("Can not run agent with empty prompt.");
        }

        // 2、tag Running state
        this.state = AgentState.RUNNING;

        // 3、 store result
        List<String> results = new ArrayList<>();

        // 4、store userPrompt
        this.messageList.add(new UserMessage(userPrompt));

        try {
            // 5、execute loop
            for (int i = 0; i < this.maxStep && this.state != AgentState.FINISHED; i++) {
                int stepNum = i + 1;
                this.currentStep = stepNum;
                log.info("Execute step: {}/{}", this.currentStep, this.maxStep);
                String result = step();
                results.add(result);
                log.info("Step finished: {}", this.currentStep);
            }

            // 6、tag finished
            if (this.currentStep >= this.maxStep) {
                this.state =AgentState.FINISHED;
                results.add("Terminated: Reached max steps {" + maxStep + "}.");
            }

            return String.join("\n", results);
        } catch (Exception e) {
            // tag error
            this.state = AgentState.ERROR;
            log.error("Executed agent error: {}", e.getMessage(), e);
            return "执行错误" + e.getMessage();
        } finally {
            // clean resources
            this.cleanUp();
        }


    }

    public abstract String step();


    protected void cleanUp() {

    }

}
