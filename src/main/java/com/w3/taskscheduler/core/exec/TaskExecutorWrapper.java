package com.w3.taskscheduler.core.exec;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.history.ExecutionHistoryStore;
import com.w3.taskscheduler.core.invoke.TaskInvoker;
import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;
import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行包装：虚拟线程提交、Semaphore 并发闸门、超时中断、顺序重试、catch(Throwable) 隔离、生成记录
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutorWrapper {
    private final ExecutorService virtualThreadExecutor; // 虚拟线程执行器
    private final TaskInvoker taskInvoker;
    private final ExecutionHistoryStore history;
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();

    public void submit(TaskDefinition def) {
        boolean concurrent = Boolean.TRUE.equals(def.allowConcurrent());
        Semaphore gate = concurrent ? null
                : gates.computeIfAbsent(def.taskId(), k -> new Semaphore(1));
        if (gate != null && !gate.tryAcquire()) {
            history.add(ExecutionRecord.start(def).fail(ExecutionStatus.SKIPPED, "任务还在执行中....."));
            return;
        }
        ExecutionRecord.start(def);
        virtualThreadExecutor.submit(() -> {
            try {
                executeWithTimeOutAndRetry(def);
            } finally {
                if (gate != null) {
                    gate.release();
                }
            }
        });
    }

    private void executeWithTimeOutAndRetry(TaskDefinition def) {
        ExecutionRecord.Builder rec = ExecutionRecord.start(def);
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> runWithRetry(def, rec), virtualThreadExecutor);
        try {
            future.get(def.timeout().toMillis(), TimeUnit.MILLISECONDS); // 超时抛 TimeoutException
        } catch (TimeoutException e) {
            future.cancel(true);
            history.add(rec.fail(ExecutionStatus.TIMEOUT, e.getMessage()));
        } catch (InterruptedException e) {
            // 停机中断
            Thread.currentThread().interrupt();
            history.add(rec.fail(ExecutionStatus.INTERRUPTED, "interrupted"));
        } catch (Exception e) {
            history.add(rec.fail(ExecutionStatus.FAILED, e.getMessage()));
        }
    }

    private void runWithRetry(TaskDefinition def, ExecutionRecord.Builder rec) {
        int attempt = 0;
        while (true) {
            attempt++;

            try {
                taskInvoker.invoke(def, new TaskContext(UUID.randomUUID().toString(), def, rec.triggeredAt()));
                history.add(rec.succeed(attempt));
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                history.add(rec.fail(ExecutionStatus.INTERRUPTED, "任务被中断"));
                return;
            } catch (Throwable t) {
                if (attempt > def.maxRetries()) {
                    log.error(
                            "任务[{}] 重试 {} 次后仍失败，放弃（不影响调度器与其他任务）",
                            def.taskId(), attempt, t
                    );
                    history.add(rec.fail(ExecutionStatus.FAILED, t.getMessage()));
                    return;
                }
            }
        }
    }
}
