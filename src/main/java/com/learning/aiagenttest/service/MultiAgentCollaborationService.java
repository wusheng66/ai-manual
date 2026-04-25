package com.learning.aiagenttest.service;

import com.learning.aiagenttest.model.multiagent.MultiAgentResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface MultiAgentCollaborationService {

    MultiAgentResponse handle(String userRequest);

    void handleByStream(String userRequest, String userId, String traceId, SseEmitter emitter);

    void handleByStreamDebug(String userRequest, String userId, String traceId, SseEmitter emitter);

    int evictCache(String userId, String intentType, String traceId);

    int evictCacheByKnowledgeVersion(String knowledgeVersion, String traceId);

    String switchKnowledgeVersion(String knowledgeVersion, boolean evictOldVersionCache, String traceId);
}
