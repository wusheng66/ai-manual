package com.learning.aiagenttest.controller;

import com.learning.aiagenttest.agent.WuManus;
import com.learning.aiagenttest.app.SystemManual;
import com.learning.aiagenttest.configure.TraceIdFilter;
import com.learning.aiagenttest.model.multiagent.MultiAgentResponse;
import com.learning.aiagenttest.model.manual.ManualUploadResult;
import com.learning.aiagenttest.service.MultiAgentCollaborationService;
import com.learning.aiagenttest.service.SystemManualService;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@RequestMapping("/sys/manual")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class SystemManualController {

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();
    private final RestTemplate restTemplate = new RestTemplate();

    @Resource
    private SystemManualService systemManualService;

    @PostMapping("/upload")
    public ManualUploadResult upload(MultipartFile file) {
        return systemManualService.parseManual(file);
    }

    @GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String previewManual(@RequestParam String markdownPath) {
        String markdown = loadMarkdown(markdownPath);
        String renderedHtml = htmlRenderer.render(markdownParser.parse(markdown));
        String safeHtml = sanitizeHtml(renderedHtml);
        String escapedMarkdownPath = markdownPath
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>手册预览</title>
                    <style>
                        :root {
                            color-scheme: dark;
                            --bg: #0f1115;
                            --panel: #171a21;
                            --border: #2c3240;
                            --text: #edf2f7;
                            --muted: #8b9bb4;
                            --accent: #6ee7ff;
                            --code: #11151d;
                        }
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            background:
                                radial-gradient(circle at top, rgba(110, 231, 255, 0.10), transparent 35%),
                                linear-gradient(180deg, #0b0d11, var(--bg));
                            color: var(--text);
                            font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
                        }
                        .shell {
                            max-width: 1080px;
                            margin: 0 auto;
                            padding: 32px 20px 48px;
                        }
                        .hero {
                            margin-bottom: 20px;
                            padding: 20px 24px;
                            border: 1px solid rgba(110, 231, 255, 0.18);
                            border-radius: 18px;
                            background: rgba(23, 26, 33, 0.88);
                            box-shadow: 0 18px 60px rgba(0, 0, 0, 0.28);
                            backdrop-filter: blur(14px);
                        }
                        .hero h1 {
                            margin: 0 0 8px;
                            font-size: 28px;
                        }
                        .hero p {
                            margin: 0;
                            color: var(--muted);
                            word-break: break-all;
                        }
                        .content {
                            padding: 28px;
                            border-radius: 20px;
                            border: 1px solid var(--border);
                            background: rgba(23, 26, 33, 0.94);
                            box-shadow: 0 18px 50px rgba(0, 0, 0, 0.22);
                            line-height: 1.8;
                        }
                        .content h1, .content h2, .content h3 {
                            line-height: 1.35;
                        }
                        .content img {
                            max-width: 100%;
                            display: block;
                            margin: 18px auto;
                            border-radius: 14px;
                            border: 1px solid var(--border);
                            box-shadow: 0 12px 28px rgba(0, 0, 0, 0.28);
                        }
                        .content table {
                            width: 100%;
                            border-collapse: collapse;
                            margin: 18px 0;
                            overflow: hidden;
                            border-radius: 12px;
                        }
                        .content th, .content td {
                            border: 1px solid var(--border);
                            padding: 10px 12px;
                            text-align: left;
                        }
                        .content th {
                            background: rgba(110, 231, 255, 0.10);
                        }
                        .content pre {
                            background: var(--code);
                            padding: 14px 16px;
                            border-radius: 14px;
                            overflow: auto;
                        }
                        .content code {
                            font-family: "Cascadia Code", Consolas, monospace;
                        }
                    </style>
                </head>
                <body>
                    <div class="shell">
                        <section class="hero">
                            <h1>系统手册预览</h1>
                            <p>Markdown 地址：%s</p>
                        </section>
                        <main class="content">%s</main>
                    </div>
                </body>
                </html>
                """.formatted(escapedMarkdownPath, safeHtml);
    }

    private String loadMarkdown(String markdownPath) {
        try {
            String markdown = restTemplate.getForObject(markdownPath, String.class);
            return markdown == null ? "" : markdown;
        } catch (RestClientException e) {
            throw new IllegalStateException("加载 Markdown 失败: " + markdownPath, e);
        }
    }

    private String sanitizeHtml(String html) {
        Safelist safelist = Safelist.relaxed()
                .addTags("table", "thead", "tbody", "tr", "th", "td")
                .addAttributes(":all", "class")
                .addAttributes("img", "src", "alt", "title")
                .addProtocols("img", "src", "http", "https");
        Document.OutputSettings outputSettings = new Document.OutputSettings();
        outputSettings.prettyPrint(false);
        return Jsoup.clean(html, "", safelist, outputSettings);
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

    @Resource
    private MultiAgentCollaborationService multiAgentCollaborationService;

    @GetMapping(value = {"/manus/chat"})
    public SseEmitter doChatWithManus(String message) {
        WuManus wuManus = new WuManus(toolCallbacks, ollamaChatModel);
        SseEmitter sseEmitter = new SseEmitter(180000L);
        wuManus.runStream(message, sseEmitter);
        return sseEmitter;
    }

    @GetMapping(value = "/manus/multi/chat")
    public MultiAgentResponse doChatWithMultiAgent(String message) {
        return multiAgentCollaborationService.handle(message);
    }

    @GetMapping(value = "/manus/multi/chat/stream")
    public SseEmitter doChatWithMultiAgentStream(String message,
                                                 @RequestParam(defaultValue = "anonymous") String userId,
                                                 @RequestParam(required = false) String traceId,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response) {
        String requestTraceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_MDC_KEY);
        String finalTraceId = (traceId == null || traceId.isBlank())
                ? (requestTraceId == null ? java.util.UUID.randomUUID().toString() : requestTraceId)
                : traceId.trim();
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Trace-Id", finalTraceId);
        SseEmitter emitter = new SseEmitter(180000L);
        multiAgentCollaborationService.handleByStream(message, userId, finalTraceId, emitter);
        return emitter;
    }

    @GetMapping(value = "/manus/multi/chat/stream/debug")
    public SseEmitter doChatWithMultiAgentStreamDebug(String message,
                                                      @RequestParam(defaultValue = "anonymous") String userId,
                                                      @RequestParam(required = false) String traceId,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        String requestTraceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_MDC_KEY);
        String finalTraceId = (traceId == null || traceId.isBlank())
                ? (requestTraceId == null ? java.util.UUID.randomUUID().toString() : requestTraceId)
                : traceId.trim();
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Trace-Id", finalTraceId);
        SseEmitter emitter = new SseEmitter(180000L);
        multiAgentCollaborationService.handleByStreamDebug(message, userId, finalTraceId, emitter);
        return emitter;
    }

    @PostMapping(value = "/manus/multi/cache/evict")
    public String evictMultiAgentCache(@RequestParam(defaultValue = "anonymous") String userId,
                                       @RequestParam(defaultValue = "ALL") String intentType,
                                       @RequestParam(required = false) String traceId) {
        int deleted = multiAgentCollaborationService.evictCache(userId, intentType, traceId);
        return "缓存清理完成，删除条目数: " + deleted + ", traceId=" + (traceId == null ? "auto-generated" : traceId);
    }

    @PostMapping(value = "/manus/multi/cache/evict/version")
    public String evictMultiAgentCacheByVersion(@RequestParam String knowledgeVersion,
                                                @RequestParam(required = false) String traceId) {
        int deleted = multiAgentCollaborationService.evictCacheByKnowledgeVersion(knowledgeVersion, traceId);
        return "按知识库版本清理完成，删除条目数: " + deleted + ", traceId=" + (traceId == null ? "auto-generated" : traceId);
    }

    @PostMapping(value = "/manus/multi/cache/version/switch")
    public String switchKnowledgeVersion(@RequestParam String knowledgeVersion,
                                         @RequestParam(defaultValue = "true") boolean evictOldVersionCache,
                                         @RequestParam(required = false) String traceId) {
        return multiAgentCollaborationService.switchKnowledgeVersion(knowledgeVersion, evictOldVersionCache, traceId);
    }
}
