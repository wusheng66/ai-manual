package com.learning.aiagenttest.controller;

import com.learning.aiagenttest.agent.WuManus;
import com.learning.aiagenttest.app.SystemManual;
import com.learning.aiagenttest.service.SystemManualService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@RequestMapping("/sys/manual")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class SystemManualController {

    @Resource
    private SystemManualService systemManualService;
    @PostMapping("/upload")
    public String upload(MultipartFile file) {
        systemManualService.parseManual(file);
        return "Ok";
    }

    @Resource
    private SystemManual systemManual;


    @GetMapping(value = "/chat/sync")
    public String doChatWithManualSync(String message, String chatId) {
        return systemManual.doChat(message, chatId);
    }

    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithSse(String message, String chatId) {
        return systemManual.doChatByStream(message, chatId).map(chuck -> ServerSentEvent.<String>builder().data(chuck).build());
    }

    @GetMapping("/chat/sse/emmitter")
    public SseEmitter doChatWithSseEmitter(String message, String chatId, HttpServletResponse response) {

        // 1. 强制设置响应头，防止被 Nginx/Gateway 缓冲
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        response.setCharacterEncoding("UTF-8");
        SseEmitter sseEmitter = new SseEmitter(180000L);
        systemManual.doChatByStream(message, chatId).subscribe(chunk -> {
            try {
                sseEmitter.send(chunk);
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
        }, sseEmitter::completeWithError, sseEmitter::complete);
        return sseEmitter;
    }
    @Autowired
    private ToolCallback[] toolCallbacks;

    @Resource
    private ChatModel ollamaChatModel;

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping(value = {"/manus/chat"})
    public SseEmitter doChatWithManus(String message) {
        WuManus wuManus = new WuManus(toolCallbacks, ollamaChatModel);
        SseEmitter sseEmitter = new SseEmitter(180000L);
        wuManus.runStream(message, sseEmitter);
        return sseEmitter;
    }


}
