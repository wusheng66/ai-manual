package com.learning.aiagenttest.service.impl;

import com.learning.aiagenttest.service.ManualImageSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class ManualImageSummaryServiceImpl implements ManualImageSummaryService {

    private final ChatClient chatClient;

    @Value("${manual.image-summary.enabled:true}")
    private boolean enabled;

    public ManualImageSummaryServiceImpl(ChatModel ollamaChatModel) {
        this.chatClient = ChatClient.builder(ollamaChatModel).build();
    }

    @Override
    public String summarize(Path imagePath, String title, String sectionPath, String ocrText) {
        if (!enabled || imagePath == null || !Files.exists(imagePath)) {
            return "";
        }
        if (ocrText == null || ocrText.isBlank()) {
            return "";
        }
        String prompt = buildPrompt(imagePath, title, sectionPath, ocrText);
        try {
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            log.warn("图片 AI 摘要生成失败: {}", imagePath, e);
            return "";
        }
    }

    private String buildPrompt(Path imagePath, String title, String sectionPath, String ocrText) {
        return """
                你是一名系统操作手册整理助手，请基于给定的图片上下文生成一段简洁、可检索的图片摘要。
                要求：
                1. 使用中文。
                2. 长度控制在 40 到 120 字。
                3. 摘要应突出页面用途、主要区域、关键按钮或字段。
                4. 不要编造 OCR 中完全没有体现的信息。
                5. 直接输出摘要正文，不要输出前缀或项目符号。

                图片文件：%s
                图片标题：%s
                所属章节：%s
                OCR 文本：
                %s
                """.formatted(imagePath.getFileName(), defaultValue(title), defaultValue(sectionPath), ocrText);
    }

    private String defaultValue(String value) {
        return value == null || value.isBlank() ? "未分类" : value;
    }
}
