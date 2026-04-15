package com.learning.aiagenttest.invoker;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SpringAiRunner implements CommandLineRunner {
    // 取消注释即可在 SpringBoot 项目启动时执行

    @Resource
    private ChatModel ollamaChatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage output = ollamaChatModel.call(new Prompt("你好，我是六子"))
                .getResult()
                .getOutput();
        System.out.println(output.getText());
    }


}
