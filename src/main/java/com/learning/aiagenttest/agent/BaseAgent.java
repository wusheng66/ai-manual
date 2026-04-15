package com.learning.aiagenttest.agent;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import com.learning.aiagenttest.model.AgentState;
import opennlp.tools.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Data
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
                String stepResult = "Step " + stepNum + ":" + result;
                results.add(stepResult);
                log.info("Step[{}] finished：{}", this.currentStep, result);
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

    class SseEmitterWrapper {
        private final SseEmitter emitter;
        private volatile boolean completed = false; // 使用 volatile 保证可见性

        public SseEmitterWrapper(SseEmitter emitter, BaseAgent baseAgent) {
            this.emitter = emitter;
            // 内部自动注册回调
            this.emitter.onCompletion(() -> {
                this.completed = true;
                baseAgent.state = AgentState.FINISHED;
            });
            this.emitter.onTimeout(() -> {
                this.completed = true;
                baseAgent.state = AgentState.FINISHED;
            });
            this.emitter.onError(e -> {
                this.completed = true;
                baseAgent.state = AgentState.ERROR;
            });
        }

        // 这就是你想要的“缺失”的方法
        public boolean isCompleted() {
            return completed;
        }

        public SseEmitter getEmitter() { return emitter; }
    }



    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return SseEmitter实例
     */
    public void runStream(String userPrompt, SseEmitter emitter) {

        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        SseEmitterWrapper emitterWrapper = new SseEmitterWrapper(emitter, this);

        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    emitter.send("错误：无法从状态运行代理: " + this.state);
                    emitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
                    return;
                }

                // 更改状态
                state = AgentState.RUNNING;
                // 记录消息上下文
                messageList.add(new UserMessage(userPrompt));

                try {
                    for (int i = 0; i < maxStep && state != AgentState.FINISHED; i++) {
                        if (emitterWrapper.isCompleted()) {
                            log.info("emitter is complete");
                            break;
                        }
                        int stepNumber = i + 1;
                        currentStep = stepNumber;
                        log.info("Executing step " + stepNumber + "/" + maxStep);

                        // 单步执行
                        String stepResult = step();
                        String result = "Step " + stepNumber + ": " + stepResult;

                        // 发送每一步的结果
                        emitter.send(result);
                    }
                    // 检查是否超出步骤限制
                    if (currentStep >= maxStep) {
                        state = AgentState.FINISHED;
                        emitter.send("执行结束: 达到最大步骤 (" + maxStep + ")");
                    }
                    // 正常完成
                    emitter.complete();
                } catch (Exception e) {
                    state = AgentState.ERROR;
                    log.error("执行智能体失败", e);
                    try {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                } finally {
                    // 清理资源
                    this.cleanUp();
                }
            } catch (Exception e) {
                log.error("执行智能体失败", e);
                try {
                    // 尝试发送错误信息，如果连接已断则会被上面的 catch 捕获
                    if (!emitterWrapper.isCompleted()) {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.complete();
                    }
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        // 设置超时和完成回调
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanUp();
            log.warn("SSE connection timed out");
        });

        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanUp();
            log.info("SSE connection completed");
        });

    }


}

