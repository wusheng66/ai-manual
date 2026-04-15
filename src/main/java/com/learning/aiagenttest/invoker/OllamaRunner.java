package com.learning.aiagenttest.invoker;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

import java.util.ArrayList;
import java.util.HashMap;

public class OllamaRunner {

    public static void main(String[] args) {
        OllamaApi ollamaApi = new OllamaApi();
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .build();
        OllamaApi.ChatResponse chatResponse = ollamaApi.chat(new OllamaApi.ChatRequest("qwen", new ArrayList<>(), false, null, null, new ArrayList<>(), new HashMap<>()));
        OllamaApi.Message message = chatResponse.message();
        System.out.println(message.content());

        String answer = chatModel.call("我是程序员六子，这是在学习Spring AI应用开发的项目！");
    }

}
