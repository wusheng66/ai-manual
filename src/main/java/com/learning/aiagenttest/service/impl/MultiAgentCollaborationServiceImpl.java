package com.learning.aiagenttest.service.impl;

import com.learning.aiagenttest.agent.ChatWorkerAgent;
import com.learning.aiagenttest.agent.QueuedAsyncAgent;
import com.learning.aiagenttest.configure.MultiAgentCacheProperties;
import com.learning.aiagenttest.model.multiagent.MultiAgentResponse;
import com.learning.aiagenttest.model.multiagent.MultiAgentStreamEvent;
import com.learning.aiagenttest.model.multiagent.SubTask;
import com.learning.aiagenttest.model.multiagent.SubTaskResult;
import com.learning.aiagenttest.model.multiagent.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.aiagenttest.service.MultiAgentCollaborationService;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MultiAgentCollaborationServiceImpl implements MultiAgentCollaborationService {

    private static final int TOP_N = 5;
    private static final int HISTORY_SIZE = 10;
    private static final String DEFAULT_KNOWLEDGE_VERSION = "v1";
    private static final String REDIS_KNOWLEDGE_VERSION_KEY_SUFFIX = ":kb:current-version";

    private final ChatModel ollamaChatModel;
    private final ToolCallback[] toolCallbacks;
    private final ObjectMapper objectMapper;
    private final VectorStore pgVectorStore;
    private final StringRedisTemplate stringRedisTemplate;
    private final MultiAgentCacheProperties cacheProperties;
    private final MeterRegistry meterRegistry;

    // 本地缓存层（进程内）
    private final Map<String, String> localAnswerCache = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> localHistoryCache = new ConcurrentHashMap<>();

    private final Map<String, QueuedAsyncAgent> queuedAgentRegistry = new ConcurrentHashMap<>();
    private final Counter localCacheHitCounter;
    private final Counter distributedCacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer answerGenerationTimer;
    private volatile String currentKnowledgeVersion = DEFAULT_KNOWLEDGE_VERSION;

    public MultiAgentCollaborationServiceImpl(ChatModel ollamaChatModel,
                                              ToolCallback[] toolCallbacks,
                                              ObjectMapper objectMapper,
                                              VectorStore pgVectorStore,
                                              StringRedisTemplate stringRedisTemplate,
                                              MultiAgentCacheProperties cacheProperties,
                                              MeterRegistry meterRegistry) {
        this.ollamaChatModel = ollamaChatModel;
        this.toolCallbacks = toolCallbacks;
        this.objectMapper = objectMapper;
        this.pgVectorStore = pgVectorStore;
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheProperties = cacheProperties;
        this.meterRegistry = meterRegistry;
        this.localCacheHitCounter = Counter.builder("multi_agent_cache_hits_total")
                .tag("layer", "local")
                .register(meterRegistry);
        this.distributedCacheHitCounter = Counter.builder("multi_agent_cache_hits_total")
                .tag("layer", "distributed")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("multi_agent_cache_miss_total")
                .register(meterRegistry);
        this.answerGenerationTimer = Timer.builder("multi_agent_answer_generation_duration")
                .publishPercentileHistogram()
                .register(meterRegistry);
        String redisVersion = stringRedisTemplate.opsForValue().get(redisKnowledgeVersionKey());
        if (redisVersion != null && !redisVersion.isBlank()) {
            this.currentKnowledgeVersion = redisVersion.trim();
        }
    }

    @Override
    public MultiAgentResponse handle(String userRequest) {
        String safeRequest = userRequest == null ? "" : userRequest.trim();
        if (safeRequest.isEmpty()) {
            throw new IllegalArgumentException("请求内容不能为空");
        }

        String intent = detectIntent(safeRequest);
        boolean needDecomposition = shouldDecompose(safeRequest);
        List<SubTask> subTasks = buildSubTasks(safeRequest, needDecomposition);

        List<SubTaskResult> taskResults = executeByQueuedAgents(safeRequest, subTasks);
        String summary = summarize(taskResults);

        return MultiAgentResponse.builder()
                .userIntent(intent)
                .decompositionEnabled(needDecomposition)
                .subTaskCount(subTasks.size())
                .summary(summary)
                .subTasks(subTasks)
                .subTaskResults(taskResults)
                .build();
    }

    @Override
    public void handleByStream(String userRequest, String userId, String traceId, SseEmitter emitter) {
        doHandleByStream(userRequest, userId, traceId, emitter, false);
    }

    @Override
    public void handleByStreamDebug(String userRequest, String userId, String traceId, SseEmitter emitter) {
        doHandleByStream(userRequest, userId, traceId, emitter, true);
    }

    private void doHandleByStream(String userRequest,
                                  String userId,
                                  String traceId,
                                  SseEmitter emitter,
                                  boolean debugDag) {
        String finalTraceId = resolveTraceId(traceId);
        CompletableFuture.runAsync(() -> {
            MDC.put("traceId", finalTraceId);
            try {
                String safeRequest = userRequest == null ? "" : userRequest.trim();
                if (safeRequest.isEmpty()) {
                    emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                            .traceId(finalTraceId)
                            .eventType("error")
                            .status("failed")
                            .content("请求不能为空")
                            .build()));
                    emitter.complete();
                    return;
                }
                emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                        .traceId(finalTraceId)
                        .eventType("start")
                        .status("running")
                        .content("任务开始执行，开始意图识别")
                        .build()));
                String safeUserId = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
                TaskType rootIntent = detectIntentWithHistory(safeRequest, safeUserId);
                boolean isComplex = isComplexQuestion(safeRequest, safeUserId);

                if (!isComplex) {
                    handleSimpleFlow(safeRequest, safeUserId, rootIntent, finalTraceId, emitter);
                    return;
                }

                emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                        .traceId(finalTraceId)
                        .eventType("planner")
                        .status("running")
                        .intentType(rootIntent.name())
                        .content("复杂问题，进入 Planner 模式进行任务分解")
                        .build()));

                List<SubTask> subTasks = buildSubTasks(safeRequest, true);
                if (debugDag) {
                    emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                            .traceId(finalTraceId)
                            .eventType("dag_preview")
                            .status("ok")
                            .content(buildDagPreviewContent(subTasks))
                            .build()));
                }
                List<CompletableFuture<SubTaskResult>> futures = submitByQueuedAgentsWithCache(safeRequest, safeUserId, subTasks);

                AtomicInteger streamIndex = new AtomicInteger(1);
                AtomicInteger nextSequenceNo = new AtomicInteger(1);
                Map<Integer, SubTaskResult> orderedBuffer = new ConcurrentHashMap<>();
                Object emitLock = new Object();

                for (CompletableFuture<SubTaskResult> future : futures) {
                    future.thenAccept(result -> {
                        synchronized (emitLock) {
                            try {
                                if (!result.isOrdered()) {
                                    emitter.send(formatStreamEvent(streamIndex.getAndIncrement(), result, finalTraceId));
                                    return;
                                }
                                orderedBuffer.put(result.getSequenceNo(), result);
                                tryDrainOrderedResults(emitter, streamIndex, nextSequenceNo, orderedBuffer, finalTraceId);
                            } catch (IOException ioException) {
                                throw new RuntimeException(ioException);
                            }
                        }
                    });
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                synchronized (emitLock) {
                    tryDrainOrderedResults(emitter, streamIndex, nextSequenceNo, orderedBuffer, finalTraceId);
                }
                emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                        .traceId(finalTraceId)
                        .eventType("done")
                        .status("finished")
                        .content("全部子任务执行完成")
                        .build()));
                emitter.complete();
                log.info("traceId={}, 多Agent流式任务执行完成", finalTraceId);
            } catch (Exception ex) {
                log.error("traceId={}, 多Agent流式任务执行失败", finalTraceId, ex);
                emitter.completeWithError(ex);
            } finally {
                MDC.remove("traceId");
            }
        });
    }

    @Override
    public int evictCache(String userId, String intentType, String traceId) {
        String finalTraceId = resolveTraceId(traceId);
        MDC.put("traceId", finalTraceId);
        String safeUserId = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
        List<TaskType> targetIntents = resolveTargetIntents(intentType);
        int deleted = 0;
        try {
            for (TaskType intent : targetIntents) {
                String historyLocalKey = safeUserId + "::" + intent.name();
                if (localHistoryCache.remove(historyLocalKey) != null) {
                    deleted++;
                }
                String historyRedisKey = redisHistoryKey(safeUserId, intent);
                Boolean historyDeleted = stringRedisTemplate.delete(historyRedisKey);
                if (Boolean.TRUE.equals(historyDeleted)) {
                    deleted++;
                }
                String answerPattern = redisAnswerKey(getCurrentKnowledgeVersion() + "::" + safeUserId + "::" + intent.name() + "::*");
                deleted += deleteRedisByPattern(answerPattern);
                deleted += deleteLocalAnswersByPrefix(getCurrentKnowledgeVersion() + "::" + safeUserId + "::" + intent.name() + "::");
            }
            log.info("traceId={}, 缓存清理完成, userId={}, intentType={}, deleted={}", finalTraceId, safeUserId, intentType, deleted);
            return deleted;
        } finally {
            MDC.remove("traceId");
        }
    }

    @Override
    public int evictCacheByKnowledgeVersion(String knowledgeVersion, String traceId) {
        String finalTraceId = resolveTraceId(traceId);
        MDC.put("traceId", finalTraceId);
        String safeVersion = sanitizeKnowledgeVersion(knowledgeVersion);
        try {
            int deleted = deleteLocalAnswersByVersion(safeVersion);
            deleted += deleteRedisByPattern(redisAnswerKey(safeVersion + "::*"));
            log.info("traceId={}, 按知识库版本清理缓存完成, version={}, deleted={}", finalTraceId, safeVersion, deleted);
            return deleted;
        } finally {
            MDC.remove("traceId");
        }
    }

    @Override
    public String switchKnowledgeVersion(String knowledgeVersion, boolean evictOldVersionCache, String traceId) {
        String finalTraceId = resolveTraceId(traceId);
        MDC.put("traceId", finalTraceId);
        String safeVersion = sanitizeKnowledgeVersion(knowledgeVersion);
        String oldVersion = getCurrentKnowledgeVersion();
        try {
            if (oldVersion.equals(safeVersion)) {
                return "知识库版本未变化，当前版本: " + safeVersion + "，traceId=" + finalTraceId;
            }
            this.currentKnowledgeVersion = safeVersion;
            stringRedisTemplate.opsForValue().set(redisKnowledgeVersionKey(), safeVersion);
            int deleted = 0;
            if (evictOldVersionCache) {
                deleted = evictCacheByKnowledgeVersion(oldVersion, finalTraceId);
            }
            log.info("traceId={}, 知识库版本切换完成, oldVersion={}, newVersion={}, deleted={}",
                    finalTraceId, oldVersion, safeVersion, deleted);
            return "知识库版本切换完成: " + oldVersion + " -> " + safeVersion
                    + "，清理旧版本缓存: " + deleted + "，traceId=" + finalTraceId;
        } finally {
            MDC.remove("traceId");
        }
    }

    private void handleSimpleFlow(String userRequest,
                                  String userId,
                                  TaskType intent,
                                  String traceId,
                                  SseEmitter emitter) throws IOException {
        String rewritten = rewriteAndNormalize(userRequest, userId, intent);
        String cacheKey = buildCacheKey(userId, intent, rewritten);
        CacheHit cacheHit = getAnswerCache(cacheKey);
        if (cacheHit.hit()) {
            emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                    .traceId(traceId)
                    .eventType("task_result")
                    .index(1)
                    .ordered(true)
                    .sequenceNo(1)
                    .taskDescription("简单问题直接回答")
                    .intentType(intent.name())
                    .agentName(resolveAgentName(intent))
                    .status("success")
                    .cacheLayer(cacheHit.layer())
                    .content(cacheHit.answer())
                    .build()));
            emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                    .traceId(traceId)
                    .eventType("done")
                    .status("finished")
                    .content("命中缓存，流程结束")
                    .build()));
            emitter.complete();
            return;
        }

        List<Document> docs = retrieveIntentTopN(rewritten, intent, TOP_N);
        String context = docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
        String answer = askIntentAgent(intent, rewritten, context, userId, "");
        putAnswerCache(cacheKey, answer);
        appendHistory(userId, intent, "Q: " + rewritten + "\nA: " + answer);

        emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                .traceId(traceId)
                .eventType("task_result")
                .index(1)
                .ordered(true)
                .sequenceNo(1)
                .taskDescription("简单问题直接回答")
                .intentType(intent.name())
                .agentName(resolveAgentName(intent))
                .status("success")
                .cacheLayer("miss")
                .content(answer)
                .build()));
        emitter.send(toJsonEvent(MultiAgentStreamEvent.builder()
                .traceId(traceId)
                .eventType("done")
                .status("finished")
                .content("简单问题处理完成")
                .build()));
        emitter.complete();
    }

    private String detectIntent(String request) {
        if (containsAny(request, "计划", "方案", "拆解", "分解", "步骤")) {
            return "PLANNING_ORCHESTRATION";
        }
        if (containsAny(request, "执行", "调用", "自动化", "脚本", "运行")) {
            return "EXECUTION_AUTOMATION";
        }
        if (containsAny(request, "生成", "写", "整理", "总结", "文档")) {
            return "CONTENT_GENERATION";
        }
        if (containsAny(request, "查询", "分析", "对比", "检索")) {
            return "RESEARCH_ANALYSIS";
        }
        return "GENERAL_ASSISTANCE";
    }

    private boolean shouldDecompose(String request) {
        return request.length() > 40
                || containsAny(request, "并且", "同时", "然后", "先", "再", "以及", "、")
                || countMatches(request, "[,，;；]") >= 2;
    }

    private List<SubTask> buildSubTasks(String request, boolean needDecomposition) {
        if (!needDecomposition) {
            boolean orderedTask = containsAny(request, "先", "然后", "再", "最后", "按顺序", "步骤");
            return List.of(SubTask.builder()
                    .id(UUID.randomUUID().toString())
                    .description(request)
                    .taskType(classifyTaskType(request))
                    .priority(1)
                    .ordered(orderedTask)
                    .sequenceNo(orderedTask ? 1 : 0)
                    .dependencyTaskIds(List.of())
                    .build());
        }
        List<SubTask> plannerTasks = planDagTasksByLlm(request);
        if (!plannerTasks.isEmpty()) {
            return plannerTasks;
        }
        return buildSubTasksByRules(request);
    }

    private List<SubTask> buildSubTasksByRules(String request) {
        List<String> roughTasks = splitByDelimiters(request);
        List<SubTask> tasks = new ArrayList<>();
        AtomicInteger priority = new AtomicInteger(1);
        AtomicInteger sequence = new AtomicInteger(1);
        String previousOrderedTaskId = null;
        for (String item : roughTasks) {
            boolean orderedSegment = containsAny(item, "先", "然后", "再", "最后", "按顺序", "步骤");
            TaskType type = classifyTaskType(item);
            List<String> fineGrained = splitByGranularity(item, orderedSegment);
            for (String granularTask : fineGrained) {
                String taskId = UUID.randomUUID().toString();
                List<String> deps;
                if (orderedSegment && previousOrderedTaskId != null) {
                    deps = List.of(previousOrderedTaskId);
                } else {
                    deps = List.of();
                }
                tasks.add(SubTask.builder()
                        .id(taskId)
                        .description(granularTask)
                        .taskType(type)
                        .priority(priority.getAndIncrement())
                        .ordered(orderedSegment)
                        .sequenceNo(orderedSegment ? sequence.getAndIncrement() : 0)
                        .dependencyTaskIds(deps)
                        .build());
                if (orderedSegment) {
                    previousOrderedTaskId = taskId;
                }
            }
        }
        return tasks;
    }

    private List<SubTask> planDagTasksByLlm(String request) {
        try {
            String plannerPrompt = """
                    你是任务规划器。请将用户复杂问题拆解成 DAG 子任务，返回 JSON 数组，不要额外解释。
                    每个元素字段：
                    - id: 子任务唯一标识（字符串）
                    - description: 子任务内容
                    - intentType: RESEARCH|PLAN|EXECUTE|GENERATE|GENERAL
                    - ordered: 是否有序
                    - sequenceNo: 有序序号（无序填0）
                    - dependencyTaskIds: 依赖任务id数组，可为空
                    - priority: 优先级（1开始）
                    用户问题：%s
                    """.formatted(request);
            String json = ChatClient.builder(ollamaChatModel).build()
                    .prompt()
                    .user(plannerPrompt)
                    .call()
                    .content();
            if (json == null || json.isBlank()) {
                return List.of();
            }
            String normalizedJson = sanitizeJsonArray(json);
            PlannerTask[] plannerTasks = objectMapper.readValue(normalizedJson, PlannerTask[].class);
            if (plannerTasks == null || plannerTasks.length == 0) {
                return List.of();
            }

            Map<String, String> idMapping = new ConcurrentHashMap<>();
            AtomicInteger sequence = new AtomicInteger(1);
            AtomicInteger priority = new AtomicInteger(1);
            List<SubTask> result = new ArrayList<>();
            for (PlannerTask plannerTask : plannerTasks) {
                if (plannerTask.description == null || plannerTask.description.isBlank()) {
                    continue;
                }
                String oldId = plannerTask.id == null || plannerTask.id.isBlank() ? UUID.randomUUID().toString() : plannerTask.id.trim();
                String newId = UUID.randomUUID().toString();
                idMapping.put(oldId, newId);
                TaskType type = parseTaskType(plannerTask.intentType, plannerTask.description);
                boolean ordered = plannerTask.ordered;
                int seqNo = ordered ? (plannerTask.sequenceNo > 0 ? plannerTask.sequenceNo : sequence.getAndIncrement()) : 0;
                int pri = plannerTask.priority > 0 ? plannerTask.priority : priority.getAndIncrement();
                result.add(SubTask.builder()
                        .id(newId)
                        .description(plannerTask.description.trim())
                        .taskType(type)
                        .priority(pri)
                        .ordered(ordered)
                        .sequenceNo(seqNo)
                        .dependencyTaskIds(plannerTask.dependencyTaskIds == null ? List.of() : plannerTask.dependencyTaskIds)
                        .build());
            }

            List<SubTask> mapped = result.stream().map(task -> SubTask.builder()
                    .id(task.getId())
                    .description(task.getDescription())
                    .taskType(task.getTaskType())
                    .priority(task.getPriority())
                    .ordered(task.isOrdered())
                    .sequenceNo(task.getSequenceNo())
                    .dependencyTaskIds(task.getDependencyTaskIds().stream()
                            .map(depId -> idMapping.getOrDefault(depId, depId))
                            .toList())
                    .build()).toList();
            return normalizeDagTasks(mapped);
        } catch (Exception ex) {
            log.warn("planner DAG 拆解失败，回退规则拆解: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<SubTask> normalizeDagTasks(List<SubTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Set<String> existingIds = tasks.stream().map(SubTask::getId).collect(Collectors.toSet());
        List<SubTask> normalized = tasks.stream().map(task -> {
            List<String> deps = task.getDependencyTaskIds() == null ? List.of() : task.getDependencyTaskIds().stream()
                    .filter(depId -> depId != null && !depId.isBlank())
                    .map(String::trim)
                    .filter(depId -> !depId.equals(task.getId()))
                    .filter(existingIds::contains)
                    .distinct()
                    .toList();
            return SubTask.builder()
                    .id(task.getId())
                    .description(task.getDescription())
                    .taskType(task.getTaskType())
                    .priority(task.getPriority())
                    .ordered(task.isOrdered())
                    .sequenceNo(task.getSequenceNo())
                    .dependencyTaskIds(deps)
                    .build();
        }).toList();

        return normalized.stream()
                .sorted(Comparator.comparingInt(SubTask::getPriority).thenComparing(SubTask::getId))
                .toList();
    }

    private List<SubTaskResult> executeByQueuedAgents(String originalRequest, List<SubTask> subTasks) {
        List<CompletableFuture<SubTaskResult>> futures = submitByQueuedAgents(originalRequest, subTasks);
        return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(SubTaskResult::getPriority))
                .collect(Collectors.toList());
    }

    private List<CompletableFuture<SubTaskResult>> submitByQueuedAgents(String originalRequest, List<SubTask> subTasks) {
        Map<TaskType, List<String>> typedAgentNames = initAgentPool();
        Map<TaskType, AtomicInteger> cursor = new EnumMap<>(TaskType.class);
        typedAgentNames.keySet().forEach(type -> cursor.put(type, new AtomicInteger(0)));

        return subTasks.stream()
                .map(task -> {
                    String agentName = pickAgentName(typedAgentNames, cursor, task.getTaskType());
                    QueuedAsyncAgent agent = queuedAgentRegistry.computeIfAbsent(agentName,
                            name -> new ChatWorkerAgent(name, ollamaChatModel, toolCallbacks));
                    return agent.submit(task, originalRequest);
                })
                .toList();
    }

    private List<CompletableFuture<SubTaskResult>> submitByQueuedAgentsWithCache(String originalRequest,
                                                                                  String userId,
                                                                                  List<SubTask> subTasks) {
        Map<String, SubTask> taskById = subTasks.stream()
                .collect(Collectors.toMap(SubTask::getId, t -> t));
        Map<String, CompletableFuture<SubTaskResult>> taskFutureMap = new ConcurrentHashMap<>();
        for (SubTask task : subTasks) {
            buildDagTaskFuture(task, taskById, taskFutureMap, userId, new HashSet<>());
        }

        return subTasks.stream()
                .map(task -> taskFutureMap.get(task.getId()))
                .toList();
    }

    private CompletableFuture<SubTaskResult> buildDagTaskFuture(SubTask task,
                                                                Map<String, SubTask> taskById,
                                                                Map<String, CompletableFuture<SubTaskResult>> taskFutureMap,
                                                                String userId,
                                                                Set<String> visiting) {
        CompletableFuture<SubTaskResult> existing = taskFutureMap.get(task.getId());
        if (existing != null) {
            return existing;
        }
        if (!visiting.add(task.getId())) {
            throw new IllegalStateException("检测到任务依赖环路, taskId=" + task.getId());
        }

        List<String> dependencyIds = task.getDependencyTaskIds() == null ? List.of() : task.getDependencyTaskIds();
        CompletableFuture<SubTaskResult> future;
        if (dependencyIds.isEmpty()) {
            future = CompletableFuture.supplyAsync(() -> executeSubTaskWithCache(task, userId, ""));
        } else {
            List<CompletableFuture<SubTaskResult>> dependencyFutures = dependencyIds.stream()
                    .map(depId -> {
                        SubTask depTask = taskById.get(depId);
                        if (depTask == null) {
                            return CompletableFuture.<SubTaskResult>completedFuture(null);
                        }
                        return buildDagTaskFuture(depTask, taskById, taskFutureMap, userId, visiting);
                    })
                    .toList();

            future = CompletableFuture.allOf(dependencyFutures.toArray(new CompletableFuture[0]))
                    .thenApplyAsync(ignored -> {
                        String dependencyContext = dependencyFutures.stream()
                                .map(CompletableFuture::join)
                                .filter(res -> res != null && res.getOutput() != null && !res.getOutput().isBlank())
                                .map(SubTaskResult::getOutput)
                                .collect(Collectors.joining("\n\n[Dependency]\n"));
                        return executeSubTaskWithCache(task, userId, dependencyContext);
                    });
        }
        taskFutureMap.put(task.getId(), future);
        visiting.remove(task.getId());
        return future;
    }

    private SubTaskResult executeSubTaskWithCache(SubTask task,
                                                  String userId,
                                                  String dependencyContext) {
            TaskType intent = task.getTaskType();
            String normalized = rewriteAndNormalize(task.getDescription(), userId, intent);
            String cacheKey = buildCacheKey(userId, intent, normalized);
            CacheHit cacheHit = getAnswerCache(cacheKey);
            if (cacheHit.hit()) {
                return SubTaskResult.builder()
                        .subTaskId(task.getId())
                        .taskDescription(task.getDescription())
                        .taskType(task.getTaskType())
                        .priority(task.getPriority())
                        .ordered(task.isOrdered())
                        .sequenceNo(task.getSequenceNo())
                        .agentName(resolveAgentName(intent))
                        .success(true)
                        .output(cacheHit.answer())
                        .build();
            }
            List<Document> docs = retrieveIntentTopN(normalized, intent, TOP_N);
            String context = docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
            String answer = askIntentAgent(intent, normalized, context, userId, dependencyContext);
            putAnswerCache(cacheKey, answer);
            appendHistory(userId, intent, "Q: " + normalized + "\nA: " + answer);

            return SubTaskResult.builder()
                    .subTaskId(task.getId())
                    .taskDescription(task.getDescription())
                    .taskType(task.getTaskType())
                    .priority(task.getPriority())
                    .ordered(task.isOrdered())
                    .sequenceNo(task.getSequenceNo())
                    .agentName(resolveAgentName(intent))
                    .success(true)
                    .output(answer)
                    .build();
    }

    private String summarize(List<SubTaskResult> taskResults) {
        long successCount = taskResults.stream().filter(SubTaskResult::isSuccess).count();
        long failedCount = taskResults.size() - successCount;
        return "任务已分发完成：总计 " + taskResults.size()
                + " 个子任务，成功 " + successCount
                + " 个，失败 " + failedCount + " 个。";
    }

    private Map<TaskType, List<String>> initAgentPool() {
        Map<TaskType, List<String>> agents = new EnumMap<>(TaskType.class);
        agents.put(TaskType.RESEARCH, List.of("Research-Agent-A", "Research-Agent-B"));
        agents.put(TaskType.PLAN, List.of("Planner-Agent-A", "Planner-Agent-B"));
        agents.put(TaskType.EXECUTE, List.of("Executor-Agent-A", "Executor-Agent-B"));
        agents.put(TaskType.GENERATE, List.of("Writer-Agent-A", "Writer-Agent-B"));
        agents.put(TaskType.GENERAL, List.of("General-Agent-A", "General-Agent-B"));
        return agents;
    }

    private String pickAgentName(Map<TaskType, List<String>> agentPool,
                                 Map<TaskType, AtomicInteger> cursor,
                                 TaskType taskType) {
        List<String> candidates = agentPool.getOrDefault(taskType, agentPool.get(TaskType.GENERAL));
        int index = Math.abs(cursor.get(taskType).getAndIncrement()) % candidates.size();
        return candidates.get(index);
    }

    private TaskType classifyTaskType(String text) {
        if (containsAny(text, "调研", "查询", "分析", "检索", "对比")) {
            return TaskType.RESEARCH;
        }
        if (containsAny(text, "规划", "计划", "拆解", "设计", "架构")) {
            return TaskType.PLAN;
        }
        if (containsAny(text, "执行", "运行", "调用", "操作", "自动化")) {
            return TaskType.EXECUTE;
        }
        if (containsAny(text, "生成", "撰写", "整理", "总结", "文档")) {
            return TaskType.GENERATE;
        }
        return TaskType.GENERAL;
    }

    private TaskType detectIntentWithHistory(String question, String userId) {
        // 简化语义模型：问题+历史拼接，优先轻量规则
        String history = String.join("\n", getHistory(userId, TaskType.GENERAL));
        String input = (history + "\n" + question).toLowerCase();
        return classifyTaskType(input);
    }

    private boolean isComplexQuestion(String question, String userId) {
        List<String> history = getHistory(userId, TaskType.GENERAL);
        if (question.length() > 60 || containsAny(question, "并且", "同时", "先", "然后", "再", "步骤", "分解")) {
            return true;
        }
        // 轻量模型兜底判断（避免纯规则误判）
        try {
            String prompt = """
                    你是意图分类器，请判断用户问题是 SIMPLE 还是 COMPLEX，只输出一个词。
                    历史: %s
                    问题: %s
                    """.formatted(String.join(" | ", history), question);
            String result = ChatClient.builder(ollamaChatModel).build()
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();
            return result != null && result.toUpperCase().contains("COMPLEX");
        } catch (Exception ex) {
            return false;
        }
    }

    private String rewriteAndNormalize(String question, String userId, TaskType intent) {
        List<String> history = getHistory(userId, intent);
        try {
            String prompt = """
                    你是问题改写与意图归一助手。
                    根据历史和当前问题，输出一句标准化问题，不要解释。
                    历史: %s
                    问题: %s
                    """.formatted(String.join(" | ", history), question);
            String normalized = ChatClient.builder(ollamaChatModel).build()
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();
            return normalized == null || normalized.isBlank() ? question.trim() : normalized.trim();
        } catch (Exception ex) {
            return question.trim();
        }
    }

    private List<Document> retrieveIntentTopN(String normalizedQuestion, TaskType intent, int topN) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(normalizedQuestion)
                    .topK(topN)
                    .filterExpression("intentType == '" + intent.name() + "'")
                    .build();
            List<Document> docs = pgVectorStore.similaritySearch(request);
            if (docs == null || docs.isEmpty()) {
                return fallbackRetrieve(normalizedQuestion, topN);
            }
            return docs;
        } catch (Exception ex) {
            return fallbackRetrieve(normalizedQuestion, topN);
        }
    }

    private List<Document> fallbackRetrieve(String normalizedQuestion, int topN) {
        try {
            SearchRequest request = SearchRequest.builder().query(normalizedQuestion).topK(topN).build();
            List<Document> docs = pgVectorStore.similaritySearch(request);
            return docs == null ? List.of() : docs;
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private String askIntentAgent(TaskType intent,
                                  String normalizedQuestion,
                                  String context,
                                  String userId,
                                  String dependencyContext) {
        String systemPrompt = switch (intent) {
            case RESEARCH -> "你是调研问答Agent，强调事实与来源依据。";
            case PLAN -> "你是规划Agent，输出步骤化执行方案。";
            case EXECUTE -> "你是执行Agent，给出可落地动作与命令。";
            case GENERATE -> "你是生成Agent，输出结构化文案结果。";
            case GENERAL -> "你是通用问答Agent。";
        };
        String history = String.join("\n", getHistory(userId, intent));
        String userPrompt = """
                标准问题: %s
                历史上下文: %s
                上游依赖输出: %s
                检索上下文(topN): %s
                要求: 使用检索上下文回答，若证据不足明确说明。
                """.formatted(normalizedQuestion, history,
                dependencyContext == null ? "" : dependencyContext,
                context);
        return answerGenerationTimer.record(() -> ChatClient.builder(ollamaChatModel)
                .defaultTools(toolCallbacks)
                .build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content());
    }

    private String redisKnowledgeVersionKey() {
        return cacheProperties.getKeyPrefix() + REDIS_KNOWLEDGE_VERSION_KEY_SUFFIX;
    }

    private List<String> splitByDelimiters(String text) {
        return List.of(text.split("[,，;；。\\n]"))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> splitByGranularity(String text, boolean orderedSegment) {
        if (text.length() < 28 && !containsAny(text, "并且", "同时", "然后", "先", "再", "以及")) {
            return List.of(text);
        }
        String splitRegex = orderedSegment ? "然后|再|最后|下一步" : "并且|同时|以及|和";
        List<String> parts = List.of(text.split(splitRegex));
        List<String> result = parts.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return result.isEmpty() ? List.of(text) : result;
    }

    private boolean containsAny(String source, String... keywords) {
        for (String keyword : keywords) {
            if (source.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int countMatches(String source, String regex) {
        return source.split(regex, -1).length - 1;
    }

    private String sanitizeJsonArray(String raw) {
        int start = raw.indexOf("[");
        int end = raw.lastIndexOf("]");
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private TaskType parseTaskType(String intentType, String description) {
        if (intentType == null || intentType.isBlank()) {
            return classifyTaskType(description);
        }
        try {
            return TaskType.valueOf(intentType.trim().toUpperCase());
        } catch (Exception ex) {
            return classifyTaskType(description);
        }
    }

    private String buildCacheKey(String userId, TaskType intent, String normalizedQuestion) {
        return getCurrentKnowledgeVersion() + "::" + userId + "::" + intent.name() + "::" + normalizedQuestion;
    }

    private String redisAnswerKey(String key) {
        return cacheProperties.getKeyPrefix() + ":answer:" + key;
    }

    private String redisHistoryKey(String userId, TaskType intent) {
        return cacheProperties.getKeyPrefix() + ":history:" + userId + "::" + intent.name();
    }

    private CacheHit getAnswerCache(String key) {
        String local = localAnswerCache.get(key);
        if (local != null) {
            localCacheHitCounter.increment();
            return new CacheHit(true, "local", local);
        }
        String distributed = stringRedisTemplate.opsForValue().get(redisAnswerKey(key));
        if (distributed != null) {
            localAnswerCache.put(key, distributed);
            distributedCacheHitCounter.increment();
            return new CacheHit(true, "distributed", distributed);
        }
        cacheMissCounter.increment();
        return new CacheHit(false, "miss", null);
    }

    private void putAnswerCache(String key, String answer) {
        localAnswerCache.put(key, answer);
        stringRedisTemplate.opsForValue().set(redisAnswerKey(key), answer,
                Duration.ofMinutes(cacheProperties.getAnswerTtlMinutes()));
    }

    private String resolveAgentName(TaskType intent) {
        return switch (intent) {
            case RESEARCH -> "Research-Agent";
            case PLAN -> "Planner-Agent";
            case EXECUTE -> "Executor-Agent";
            case GENERATE -> "Writer-Agent";
            case GENERAL -> "General-Agent";
        };
    }

    private List<String> getHistory(String userId, TaskType intent) {
        String key = userId + "::" + intent.name();
        Deque<String> local = localHistoryCache.get(key);
        if (local != null && !local.isEmpty()) {
            return new ArrayList<>(local);
        }
        String redisKey = redisHistoryKey(userId, intent);
        List<String> distributed = stringRedisTemplate.opsForList().range(redisKey, 0, HISTORY_SIZE - 1);
        if (distributed == null || distributed.isEmpty()) {
            return Collections.emptyList();
        }
        Deque<String> historyQueue = new LinkedList<>(distributed);
        localHistoryCache.put(key, historyQueue);
        return new ArrayList<>(historyQueue);
    }

    private void appendHistory(String userId, TaskType intent, String message) {
        String key = userId + "::" + intent.name();
        localHistoryCache.computeIfAbsent(key, k -> new LinkedList<>());
        appendWithLimit(localHistoryCache.get(key), message);
        String redisKey = redisHistoryKey(userId, intent);
        stringRedisTemplate.opsForList().leftPush(redisKey, message);
        stringRedisTemplate.opsForList().trim(redisKey, 0, HISTORY_SIZE - 1);
        stringRedisTemplate.expire(redisKey, Duration.ofMinutes(cacheProperties.getHistoryTtlMinutes()));
    }

    private List<TaskType> resolveTargetIntents(String intentType) {
        if (intentType == null || intentType.isBlank() || "ALL".equalsIgnoreCase(intentType)) {
            return List.of(TaskType.values());
        }
        return List.of(TaskType.valueOf(intentType.trim().toUpperCase()));
    }

    private int deleteRedisByPattern(String pattern) {
        var keys = stringRedisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = stringRedisTemplate.delete(keys);
        return deleted == null ? 0 : deleted.intValue();
    }

    private int deleteLocalAnswersByPrefix(String prefix) {
        List<String> keys = localAnswerCache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList();
        keys.forEach(localAnswerCache::remove);
        return keys.size();
    }

    private int deleteLocalAnswersByVersion(String knowledgeVersion) {
        String prefix = knowledgeVersion + "::";
        List<String> keys = localAnswerCache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList();
        keys.forEach(localAnswerCache::remove);
        return keys.size();
    }

    private String sanitizeKnowledgeVersion(String knowledgeVersion) {
        if (knowledgeVersion == null || knowledgeVersion.isBlank()) {
            throw new IllegalArgumentException("knowledgeVersion 不能为空");
        }
        return knowledgeVersion.trim();
    }

    private String getCurrentKnowledgeVersion() {
        return currentKnowledgeVersion;
    }

    private void appendWithLimit(Deque<String> queue, String message) {
        queue.addLast(message);
        while (queue.size() > HISTORY_SIZE) {
            queue.removeFirst();
        }
    }

    private void tryDrainOrderedResults(SseEmitter emitter,
                                        AtomicInteger streamIndex,
                                        AtomicInteger nextSequenceNo,
                                        Map<Integer, SubTaskResult> orderedBuffer,
                                        String traceId) throws IOException {
        while (true) {
            int nextSeq = nextSequenceNo.get();
            SubTaskResult nextResult = orderedBuffer.remove(nextSeq);
            if (nextResult == null) {
                return;
            }
            emitter.send(formatStreamEvent(streamIndex.getAndIncrement(), nextResult, traceId));
            nextSequenceNo.incrementAndGet();
        }
    }

    private String formatStreamEvent(int streamIndex, SubTaskResult result, String traceId) throws IOException {
        MultiAgentStreamEvent event = MultiAgentStreamEvent.builder()
                .traceId(traceId)
                .eventType("task_result")
                .index(streamIndex)
                .taskId(result.getSubTaskId())
                .taskDescription(result.getTaskDescription())
                .sequenceNo(result.isOrdered() ? result.getSequenceNo() : null)
                .ordered(result.isOrdered())
                .agentName(result.getAgentName())
                .status(result.isSuccess() ? "success" : "failed")
                .content(result.getOutput())
                .build();
        return toJsonEvent(event);
    }

    private String toJsonEvent(MultiAgentStreamEvent event) throws IOException {
        return objectMapper.writeValueAsString(event);
    }

    private String buildDagPreviewContent(List<SubTask> subTasks) {
        try {
            List<Map<String, Object>> dag = subTasks.stream().map(task -> Map.of(
                    "taskId", task.getId(),
                    "description", task.getDescription(),
                    "intentType", task.getTaskType().name(),
                    "priority", task.getPriority(),
                    "ordered", task.isOrdered(),
                    "sequenceNo", task.getSequenceNo(),
                    "dependencyTaskIds", task.getDependencyTaskIds()
            )).toList();
            return objectMapper.writeValueAsString(dag);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String resolveTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return traceId.trim();
    }

    private static class PlannerTask {
        public String id;
        public String description;
        public String intentType;
        public boolean ordered;
        public int sequenceNo;
        public List<String> dependencyTaskIds;
        public int priority;
    }

    private record CacheHit(boolean hit, String layer, String answer) {
    }
}
