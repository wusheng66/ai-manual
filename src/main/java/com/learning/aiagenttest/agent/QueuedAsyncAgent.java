package com.learning.aiagenttest.agent;

import com.learning.aiagenttest.model.multiagent.SubTask;
import com.learning.aiagenttest.model.multiagent.SubTaskResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 队列式异步 Agent 基类：
 * - 当前 agent 忙时，任务进入等待队列
 * - 同一个 agent 内部按队列顺序执行
 * - 调用方异步获取任务结果
 */
@Slf4j
public abstract class QueuedAsyncAgent {

    @Getter
    private final String agentName;
    private final BlockingQueue<QueuedTask> waitingQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean processing = new AtomicBoolean(false);

    protected QueuedAsyncAgent(String agentName) {
        this.agentName = agentName;
    }

    public CompletableFuture<SubTaskResult> submit(SubTask subTask, String originalRequest) {
        CompletableFuture<SubTaskResult> future = new CompletableFuture<>();
        waitingQueue.offer(new QueuedTask(subTask, originalRequest, future));
        schedule();
        return future;
    }

    private void schedule() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(this::drainQueue);
    }

    private void drainQueue() {
        try {
            QueuedTask task;
            while ((task = waitingQueue.poll()) != null) {
                executeOne(task);
            }
        } finally {
            processing.set(false);
            if (!waitingQueue.isEmpty()) {
                schedule();
            }
        }
    }

    private void executeOne(QueuedTask queuedTask) {
        try {
            SubTaskResult result = doExecute(queuedTask.subTask(), queuedTask.originalRequest());
            queuedTask.future().complete(result);
        } catch (Exception ex) {
            log.error("agent 执行异常, agent={}, taskId={}", agentName, queuedTask.subTask().getId(), ex);
            queuedTask.future().complete(SubTaskResult.builder()
                    .subTaskId(queuedTask.subTask().getId())
                    .taskDescription(queuedTask.subTask().getDescription())
                    .agentName(agentName)
                    .taskType(queuedTask.subTask().getTaskType())
                    .priority(queuedTask.subTask().getPriority())
                    .ordered(queuedTask.subTask().isOrdered())
                    .sequenceNo(queuedTask.subTask().getSequenceNo())
                    .success(false)
                    .output("执行失败: " + ex.getMessage())
                    .build());
        }
    }

    protected abstract SubTaskResult doExecute(SubTask subTask, String originalRequest);

    private record QueuedTask(
            SubTask subTask,
            String originalRequest,
            CompletableFuture<SubTaskResult> future
    ) {
    }
}
