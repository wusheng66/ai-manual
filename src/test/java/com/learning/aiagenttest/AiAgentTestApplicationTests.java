package com.learning.aiagenttest;

import com.learning.aiagenttest.app.SystemManual;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@SpringBootTest
class AiAgentTestApplicationTests {
    @Resource
    private SystemManual systemManual;
    @Resource
    private VectorStore pgVectorStore;


    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是小六";
        String answer = systemManual.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "我想了解一下当前系统（PSM）的主要内容";
        answer = systemManual.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "刚刚说的系统是什么？刚跟你说过，帮我回忆一下";
        answer = systemManual.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }
    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是六子，我想知道PSM如何使用";
        SystemManual.ExecuteSteps executeSteps = systemManual.doChatWithReport(message, chatId);
        Assertions.assertNotNull(executeSteps);
    }

    @Test
    void doVectorStore() {

        SearchRequest request = SearchRequest.builder()
                .query("什么是PSM？")
                .topK(5)                  // 返回最相似的5个结果
                .similarityThreshold(0.7) // 相似度阈值，0.0-1.0之间
                .filterExpression("category == 'web' AND date > '2025-05-03'")  // 过滤表达式
                .build();

        List<Document> results = pgVectorStore.similaritySearch(request);

        log.info("test");

        Optional.ofNullable(results).ifPresent(list -> list.forEach(document -> {
            log.info("document content: {}", document.getText());
        }));
    }


}
