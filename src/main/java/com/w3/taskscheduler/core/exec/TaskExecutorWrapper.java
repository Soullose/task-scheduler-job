package com.w3.taskscheduler.core.exec;

import java.time.Duration;
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
 * 任务执行包装器：负责一次任务触发的完整生命周期。
 * <ul>
 * <li>按 {@code allowConcurrent} 决定是否使用 Semaphore 做任务级并发闸门，默认串行，避免同一任务重叠执行；</li>
 * <li>将实际执行提交到虚拟线程，调度线程不被业务阻塞；</li>
 * <li>用 {@code future.get(timeout, ...)} 施加超时控制，超时后取消并记录 TIMEOUT；</li>
 * <li>业务失败时按 {@code maxRetries} 顺序重试，每次触发最终都会生成一条执行记录；</li>
 * <li>catch(Throwable) 隔离单个任务的异常，不影响调度器与其他任务。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutorWrapper {
    private final ExecutorService virtualThreadExecutor; // 虚拟线程执行器
    private final TaskInvoker taskInvoker; // 反射调用任务 handler（FQCN + execute(TaskContext)）
    private final ExecutionHistoryStore history; // 执行记录出口：发布事件供持久化/查询
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>(); // 按 taskId 隔离的并发闸门（仅 allowConcurrent=false
                                                                            // 时使用）

    /** 任务未显式配置 timeout 时的兜底超时，避免 timeout 为 null 直接 NPE */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 提交一次任务触发：
     * <ol>
     * <li>非并发任务先抢闸门，抢不到说明上一轮还在执行，直接生成 SKIPPED 记录并返回；</li>
     * <li>抢到闸门后将实际执行放入虚拟线程，finally 中保证闸门最终释放。</li>
     * </ol>
     *
     * @param def 本次触发的任务定义
     */
    public void submit(TaskDefinition def) {
        // allowConcurrent=true 时不用闸门，允许同一任务并发执行；否则每个 taskId 一个 Semaphore(1)
        boolean concurrent = Boolean.TRUE.equals(def.allowConcurrent());
        Semaphore gate = concurrent ? null
                : gates.computeIfAbsent(def.taskId(), k -> new Semaphore(1));
        // 闸门被占用 → 上一轮尚未结束，跳过本次触发（不排队）
        if (gate != null && !gate.tryAcquire()) {
            history.add(ExecutionRecord.start(def).fail(ExecutionStatus.SKIPPED, "任务还在执行中....."));
            return;
        }
        // 提交到虚拟线程执行，调度线程立即返回；finally 保证闸门最终释放
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

    /**
     * 执行一次触发并施加超时控制：
     * <ol>
     * <li>先创建执行记录（此时打点 triggeredAt/startAt），无论结果如何都会有一条记录落库；</li>
     * <li>用 {@code runAsync} 把带重试的执行丢进虚拟线程，当前线程只负责等待/超时；</li>
     * <li>超时 → 取消并记录 TIMEOUT；线程被中断 → 记录 INTERRUPTED；其他异常统一记录 FAILED。</li>
     * </ol>
     *
     * @param def 任务定义
     */
    private void executeWithTimeOutAndRetry(TaskDefinition def) {
        ExecutionRecord.Builder rec = ExecutionRecord.start(def);
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> runWithRetry(def, rec), virtualThreadExecutor);
        try {
            // 仅当 timeout 显式配置为非 null、非零、非负时才采用配置值，否则用 DEFAULT_TIMEOUT
            Duration timeout = def.timeout() == null || def.timeout().isZero() || def.timeout().isNegative()
                    ? DEFAULT_TIMEOUT
                    : def.timeout();
            if (def.timeout() == null) {
                log.warn("任务 [{}] 未配置 timeout，使用默认值 {}", def.name(), DEFAULT_TIMEOUT);
            }
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); // 等待执行完成；超过 timeout 抛 TimeoutException
        } catch (TimeoutException e) {
            future.cancel(true); // 标记取消；注意 CompletableFuture 不会真正中断已启动的虚拟线程，后台仍可能执行完并补写记录
            history.add(rec.fail(ExecutionStatus.TIMEOUT, e.getMessage()));
        } catch (InterruptedException e) {
            // 停机/中断：恢复中断标记，避免吞掉线程中断状态
            Thread.currentThread().interrupt();
            history.add(rec.fail(ExecutionStatus.INTERRUPTED, "interrupted"));
        } catch (Exception e) {
            // 任何未预期异常（如 timeout 为 null 的 NPE）统一记为 FAILED
            history.add(rec.fail(ExecutionStatus.FAILED, e.getMessage()));
        }
    }

    /**
     * 带顺序重试的实际业务执行（运行在虚拟线程中）：
     * <ol>
     * <li>每轮循环 attempt 自增，调用 {@link TaskInvoker#invoke} 执行 handler；</li>
     * <li>成功 → 记录 SUCCESS（写入真实尝试次数）；</li>
     * <li>被中断 → 记录 INTERRUPTED 并结束；</li>
     * <li>其他 Throwable → 未超过 maxRetries 则继续下一轮，超过则记录 FAILED 放弃。</li>
     * </ol>
     * 注意：当前实现未使用 {@code retryDelay}，失败后立即重试；且失败记录中的 attempts 取自 Builder
     * （未调用 noteAttempt 时恒为 0），与成功记录的真实尝试次数口径不一致。
     *
     * @param def 任务定义
     * @param rec 当前触发对应的执行记录构建器
     */
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
                // 未超过 maxRetries：继续下一轮重试（当前未应用 retryDelay，立即重试）
            }
        }
    }
}
