package com.learning.aiagenttest.invoker;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class LangChainRunner {

    public static void main(String[] args) {
        ChatLanguageModel qwenModel = QwenChatModel.builder()
                .apiKey("sk-5df1e35c34ed47f6a1415d92d5f17dc6")
                .modelName("qwen-max")
                .build();
        String answer = qwenModel.chat("我是程序员六子，这是在学习Spring AI应用开发的项目！");
        System.out.println(answer);
    }

}
