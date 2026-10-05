package com.w3.taskscheduler.core.exec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.github.f4b6a3.uuid.UuidCreator;
import com.w3.taskscheduler.core.history.ExecutionHistoryStore;
import com.w3.taskscheduler.core.invoke.TaskInvoker;
import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;
import com.w3.taskscheduler.core.model.Outcome;
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
 *
 * <p><b>并发闸门的生命周期（防内存堆积的关键）</b>：闸门由真正执行业务的虚拟线程在
 * <b>业务实际结束</b>时释放，而不是在 {@code timeout} 到点、写下 TIMEOUT 记录时释放。
 * 原因是超时取消是<b>协作式</b>的：{@code FutureTask.cancel(true)} 只保证发出中断信号，
 * 业务若不响应中断（不可中断的 IO/锁等待/忽略 InterruptedException）会继续在后台运行，
 * 其整个对象图（大报文、附件字节、查询结果集……）仍被强引用。若此时就归还闸门，
 * 下一次触发会立刻启动同一任务的又一份执行，超时越久堆积越多（每份都占着堆），
 * 最终把堆吃光——这正是"进程跑不长"的根因之一。
 * 因此超时后闸门<b>保持占用</b>，后续触发一律记 SKIPPED 并直接返回，
 * 直到那份执行真正结束为止（单任务同时最多只有一份在跑）。
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
     * <li>非并发任务先抢闸门，抢不到说明上一轮还在执行（含超时后仍在后台收尾的那一轮），
     *     直接生成 SKIPPED 记录并返回，不排队、不派生新执行；</li>
     * <li>抢到闸门后把实际执行放入虚拟线程，闸门由业务线程在真正结束时释放。</li>
     * </ol>
     *
     * @param def 本次触发的任务定义
     */
    public void submit(TaskDefinition def) {
        virtualThreadExecutor.submit(() -> executeWithGate(def));
    }

    /**
     * 遗忘指定任务的并发闸门（任务被注销/停用后调用），避免 {@code gates} 条目只增不减。
     * <p>
     * 仅当闸门当前空闲（仍有 1 个许可）时才移除：先用 {@code tryAcquire} 占住再移除，
     * 保证不会把「正在被某次执行持有」的闸门从映射里摘掉——否则下一次提交会新建一个闸门，
     * 同一任务又可能出现两份并发执行。闸门仍在占用时保留条目，是刻意为之的安全取舍。
     *
     * @param taskId 任务 ID
     */
    public void forgetGate(String taskId) {
        Semaphore gate = gates.get(taskId);
        if (gate == null) {
            return;
        }
        if (!gate.tryAcquire()) {
            log.debug("event=gate.forget.skip taskId={} 闸门仍被占用（执行收尾中），保留条目", taskId);
            return;
        }
        try {
            gates.remove(taskId, gate);
        } finally {
            gate.release();
        }
    }

    private void executeWithGate(TaskDefinition def) {
        // allowConcurrent=true 时不用闸门，允许同一任务并发执行；否则每个 taskId 一个 Semaphore(1)
        boolean concurrent = Boolean.TRUE.equals(def.allowConcurrent());
        Semaphore gate = concurrent ? null
                : gates.computeIfAbsent(def.taskId(), k -> new Semaphore(1));
        // 闸门被占用 → 上一轮尚未真正结束（可能是超时后仍在后台运行的那一轮），跳过本次触发（不排队）
        if (gate != null && !gate.tryAcquire()) {
            history.add(ExecutionRecord.start(def).fail(ExecutionStatus.SKIPPED, "任务还在执行中....."));
            return;
        }
        // 刻意不在这里 finally 归还闸门：许可的归还只由两条确定路径负责（提交被拒 / 业务真正结束），
        // 由 permitReleased 的 CAS 保证恰好归还一次，避免重复归还把信号量许可放大成 2 而导致并发重叠
        executeWithTimeOutAndRetry(def, gate);
    }

    /**
     * 执行一次触发并施加超时控制：
     * <ol>
     * <li>先创建执行记录（此时打点 triggeredAt/startAt），无论结果如何都会有一条记录落库；</li>
     * <li>用 {@link FutureTask} 把带重试的执行丢进虚拟线程，当前线程只负责等待/超时；</li>
     * <li>超时 → 取消并记录 TIMEOUT，但<b>不</b>归还闸门（业务可能仍在跑，见类注释）；</li>
     * <li>线程被中断 → 记录 INTERRUPTED；其他异常统一记录 FAILED。</li>
     * </ol>
     *
     * @param def  任务定义
     * @param gate 任务级并发闸门（{@code allowConcurrent=true} 时为 {@code null}）
     */
    private void executeWithTimeOutAndRetry(TaskDefinition def, Semaphore gate) {
        ExecutionRecord.Builder rec = ExecutionRecord.start(def);

        AtomicBoolean terminal = new AtomicBoolean(false); // 本次触发的终态写锁：谁先抢到谁写
        AtomicInteger attemptCounter = new AtomicInteger(); // 真实尝试次数，超时/失败时也能拿到
        AtomicBoolean started = new AtomicBoolean(false); // 业务是否已在虚拟线程中真正开始
        AtomicBoolean timedOut = new AtomicBoolean(false); // 是否已按超时放弃等待
        AtomicBoolean permitReleased = new AtomicBoolean(gate == null); // 闸门许可是否已归还（幂等释放的 CAS 位）

        FutureTask<Outcome> future = new FutureTask<>(() -> {
            started.set(true);
            if (timedOut.get()) {
                // 已在超时窗口里被放弃：不再执行业务，直接结束并归还闸门
                releaseGate(gate, permitReleased);
                return Outcome.interrupted();
            }
            try {
                return runWithRetry(def, rec);
            } finally {
                // 关键：闸门在业务真正结束时才归还，超时提前返回不会走到这里
                releaseGate(gate, permitReleased);
            }
        });
        try {
            virtualThreadExecutor.execute(future);
        } catch (RuntimeException e) {
            // 执行器已关闭/拒绝（RejectedExecutionException）：业务不会开始，立即归还闸门并记录失败
            releaseGate(gate, permitReleased);
            recordOnce(rec, terminal, attemptCounter, ExecutionStatus.FAILED,
                    "任务提交被拒绝：" + e.getMessage(), attemptCounter.get());
            return;
        }

        try {
            // 仅当 timeout 显式配置为非 null、非零、非负时才采用配置值，否则用 DEFAULT_TIMEOUT
            Duration timeout = def.timeout() == null || def.timeout().isZero() || def.timeout().isNegative()
                    ? DEFAULT_TIMEOUT
                    : def.timeout();
            if (def.timeout() == null) {
                log.warn("任务 [{}] 未配置 timeout，使用默认值 {}", def.name(), DEFAULT_TIMEOUT);
            }
            Outcome outcome = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); // 等待执行完成；超过 timeout 抛
                                                                                     // TimeoutException
            int attempt = outcome.status() == ExecutionStatus.SUCCESS
                    ? outcome.attempts()
                    : attemptCounter.get();
            recordOnce(rec, terminal, attemptCounter, outcome.status(), outcome.message(), attempt);
        } catch (TimeoutException e) {
            // 先置超时标记再取消：保证业务线程要么完整跑完（自己归还闸门），
            // 要么在开始前就放弃（由本方法归还闸门），两条路径都不会重复归还
            timedOut.set(true);
            future.cancel(true); // 发出中断信号；业务不响应中断时会继续在后台运行
            recordOnce(
                    rec, terminal, attemptCounter, ExecutionStatus.TIMEOUT,
                    "任务超时：" + e.getMessage(), attemptCounter.get()
            );
            if (!started.get()) {
                // 业务还没来得及开始就被取消：立即归还闸门，避免该任务以后再也不能被调度
                releaseGate(gate, permitReleased);
            } else {
                // 业务仍在虚拟线程中收尾（中断是协作式的）：闸门保持占用，后续触发会被记 SKIPPED，
                // 以此把「同一任务的并发份数」钉死在 1，避免慢任务被反复触发导致内存成倍堆积
                log.warn(
                        "event=task.stuck taskId={} name={} 已超时但执行尚未结束：并发闸门保持占用，"
                                + "后续触发将记为 SKIPPED，直至该次执行真正结束（必要时请停用该任务并重启进程）",
                        def.taskId(), def.name()
                );
            }
        } catch (InterruptedException e) {
            // 停机/中断：恢复中断标记，避免吞掉线程中断状态
            Thread.currentThread().interrupt();
            // 被超时取消时，TIMEOUT 记录已由取消方写入，这里不要再补
            if (!future.isCancelled()) {
                recordOnce(
                        rec, terminal, attemptCounter, ExecutionStatus.INTERRUPTED,
                        "任务被中断", attemptCounter.get()
                );
            }
            return;

        } catch (Exception e) {
            // 任何未预期异常（如 timeout 为 null 的 NPE）统一记为 FAILED
            recordOnce(
                    rec, terminal, attemptCounter, ExecutionStatus.FAILED,
                    "任务异常：" + e.getMessage(), attemptCounter.get()
            );
        }
    }

    /** 幂等归还闸门许可：同一份执行无论走哪条收尾路径，都只会真正 release 一次 */
    private static void releaseGate(Semaphore gate, AtomicBoolean permitReleased) {
        if (gate != null && permitReleased.compareAndSet(false, true)) {
            gate.release();
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
    private Outcome runWithRetry(TaskDefinition def, ExecutionRecord.Builder rec) {
        int attempt = 0;
        while (true) {
            attempt++;

            try {
                taskInvoker.invoke(
                        def, new TaskContext(UuidCreator.getTimeOrderedEpoch().toString(), def, rec.triggeredAt())
                );
                return Outcome.success(attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Outcome.interrupted(); // 不写库！这个线程已被中断，不能再碰数据库
            } catch (Throwable t) {
                if (attempt > def.maxRetries()) {
                    log.error(
                            "任务[{}] 重试 {} 次后仍失败，放弃（不影响调度器与其他任务）",
                            def.taskId(), attempt, t
                    );
                    return Outcome.failed(t.getMessage());
                }
                // 未超过 maxRetries：继续下一轮重试（当前未应用 retryDelay，立即重试）
            }
        }
    }

    private void recordOnce(ExecutionRecord.Builder rec, AtomicBoolean terminal,
            AtomicInteger attemptCounter, ExecutionStatus status,
            String msg, int attempt) {
        if (!terminal.compareAndSet(false, true)) {
            log.warn("本次触发已有终态记录，丢弃重复记录 status={}", status); // 双写保护
            return;
        }
        rec.noteAttempt(attempt);
        if (status == ExecutionStatus.SUCCESS) {
            history.add(rec.succeed(attempt));
        } else {
            history.add(rec.fail(status, msg));
        }
    }
}
