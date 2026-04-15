package com.learning.aiagenttest.invoker;

// 建议dashscope SDK的版本 >= 2.12.0

import java.util.ArrayList;
import java.util.Arrays;
import java.lang.System;
import java.util.List;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.JsonUtils;

public class SdkAiInvoker {
    private static final String API_KEY = "sk-5df1e35c34ed47f6a1415d92d5f17dc6";

    public static GenerationResult callWithMessage() throws ApiException, NoApiKeyException, InputRequiredException {
        Generation generation = new Generation();
        List<Message> messages = new ArrayList<>();
        Message systemMessage = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("Who are you?").build();

        Message userMessage = Message.builder()
                .role(Role.USER.getValue())
                .content("你是谁？").build();

        messages.add(systemMessage);
        messages.add(userMessage);


        GenerationParam generationParam = GenerationParam.builder()
                .apiKey(API_KEY)
                .model("qwen-plus")
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();

        return generation.call(generationParam);
    }

    public static void main(String[] args) {
        try {
            GenerationResult result = callWithMessage();
            System.out.println(JsonUtils.toJson(result));
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            // 使用日志框架记录异常信息
            System.err.println("An error occurred while calling the generation service: " + e.getMessage());
        }
        System.exit(0);
    }
}

