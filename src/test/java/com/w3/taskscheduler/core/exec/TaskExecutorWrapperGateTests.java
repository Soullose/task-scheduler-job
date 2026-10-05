package com.w3.taskscheduler.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.w3.taskscheduler.core.history.ExecutionHistoryStore;
import com.w3.taskscheduler.core.invoke.TaskInvoker;
import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;
import com.w3.taskscheduler.core.model.TaskDefinition;

/**
 * {@link TaskExecutorWrapper} 并发闸门生命周期测试。
 *
 * <p>核心不变量：{@code allowConcurrent=false} 的任务，同一时刻最多只有一份执行在跑——
 * 包括「已按 timeout 记了 TIMEOUT、但业务不响应中断仍在后台收尾」的那一份。
 * 旧实现会在超时点归还闸门，导致慢任务被反复触发、每份都占着一份完整对象图（内存成倍堆积），
 * 本用例钉住修复后的语义。</p>
 */
class TaskExecutorWrapperGateTests {

    private ExecutorService executor;
    private TaskInvoker invoker;
    private ExecutionHistoryStore history;
    private TaskExecutorWrapper wrapper;
    private final List<ExecutionRecord> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("test-vt-", 0).factory());
        invoker = mock(TaskInvoker.class);
        history = mock(ExecutionHistoryStore.class);
        doAnswer(inv -> {
            records.add(inv.getArgument(0));
            return null;
        }).when(history).add(any(ExecutionRecord.class));
        wrapper = new TaskExecutorWrapper(executor, invoker, history);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 超时后业务仍在跑：闸门必须保持占用，不再放行同一任务的第二份执行 ----------

    @Test
    void stuckExecutionKeepsGateAndBlocksNextTrigger() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        AtomicBoolean releaseStuck = new AtomicBoolean(false);
        AtomicInteger handlerCalls = new AtomicInteger();

        doAnswer(inv -> {
            if (handlerCalls.incrementAndGet() == 1) {
                firstEntered.countDown();
                // 故意忽略中断：模拟不可中断的 IO/锁等待（超时取消对它无效）
                while (!releaseStuck.get()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        // 吞掉中断，继续跑
                    }
                }
            }
            return null;
        }).when(invoker).invoke(any(TaskDefinition.class), any());

        TaskDefinition def = def("stuck-task", Duration.ofMillis(200), false);

        wrapper.submit(def);
        assertTrue(firstEntered.await(3, TimeUnit.SECONDS), "业务应已开始执行");
        assertTrue(waitUntil(() -> hasStatus(ExecutionStatus.TIMEOUT), 3000), "超时应写入 TIMEOUT 记录");

        // 关键断言：超时后闸门仍被占用 → 第二次触发被跳过，且不会真的进入 handler
        wrapper.submit(def);
        assertTrue(waitUntil(() -> hasStatus(ExecutionStatus.SKIPPED), 3000), "闸门被占用时应记 SKIPPED");
        assertEquals(1, handlerCalls.get(), "超时后仍在运行的执行不得被同一任务的新触发并发叠加");

        // 业务真正结束后闸门归还，后续触发恢复正常
        releaseStuck.set(true);
        assertTrue(waitUntil(() -> {
            if (handlerCalls.get() < 2) {
                wrapper.submit(def);
            }
            return handlerCalls.get() >= 2;
        }, 5000), "业务结束后应重新可以执行（闸门已归还）");
    }

    // ---------- 业务响应中断：中断使其结束，闸门由业务线程归还，任务不会被永久卡死 ----------

    @Test
    void cooperativeInterruptReleasesGateEventually() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger();
        doAnswer(inv -> {
            handlerCalls.incrementAndGet();
            Thread.sleep(10_000); // 可中断阻塞：cancel(true) 后抛 InterruptedException
            return null;
        }).when(invoker).invoke(any(TaskDefinition.class), any());

        TaskDefinition def = def("interruptible-task", Duration.ofMillis(200), false);
        wrapper.submit(def);
        assertTrue(waitUntil(() -> hasStatus(ExecutionStatus.TIMEOUT), 3000), "超时应写入 TIMEOUT 记录");

        assertTrue(waitUntil(() -> {
            if (handlerCalls.get() < 2) {
                wrapper.submit(def);
            }
            return handlerCalls.get() >= 2;
        }, 5000), "被中断结束后闸门应归还，任务应能再次执行");
    }

    // ---------- allowConcurrent=true：不加闸门，允许同一任务重叠执行（回归保护） ----------

    @Test
    void concurrentTaskStillAllowsOverlap() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        doAnswer(inv -> {
            bothEntered.countDown();
            bothEntered.await(3, TimeUnit.SECONDS);
            return null;
        }).when(invoker).invoke(any(TaskDefinition.class), any());

        TaskDefinition def = def("concurrent-task", Duration.ofSeconds(5), true);
        wrapper.submit(def);
        wrapper.submit(def);

        assertTrue(bothEntered.await(3, TimeUnit.SECONDS), "allow-concurrent=true 应允许两份执行同时在跑");
    }

    // ---------- gates 映射清理：空闲条目可遗忘，被占用的条目必须保留 ----------

    @Test
    void forgetGateRemovesIdleEntryAndKeepsBusyEntry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean(false);
        doAnswer(inv -> {
            entered.countDown();
            while (!release.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // 吞掉中断
                }
            }
            return null;
        }).when(invoker).invoke(any(TaskDefinition.class), any());

        TaskDefinition def = def("gate-task", Duration.ofMillis(150), false);

        // 运行中（占用）→ forgetGate 必须保留条目，否则新触发会新建闸门造成并发叠加
        wrapper.submit(def);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        wrapper.forgetGate(def.taskId());
        assertEquals(1, gateMap().size(), "闸门被占用时不得移除条目");

        release.set(true);
        assertTrue(waitUntil(() -> permits(def.taskId()) == 1, 5000), "业务结束后许可应归还");

        // 空闲 → forgetGate 移除条目（避免 gates 只增不减）
        wrapper.forgetGate(def.taskId());
        assertEquals(0, gateMap().size(), "空闲闸门应被遗忘");
    }

    // ---------- 提交被拒（停机/执行器关闭）：不得泄漏许可 ----------

    @Test
    void rejectedSubmissionReleasesPermit() throws Exception {
        ExecutorService rejectAfterFirst = new RejectAfterFirst(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("reject-vt-", 0).factory()));
        try {
            TaskExecutorWrapper rejectingWrapper = new TaskExecutorWrapper(rejectAfterFirst, invoker, history);
            doAnswer(inv -> null).when(invoker).invoke(any(TaskDefinition.class), any());

            TaskDefinition def = def("reject-task", Duration.ofSeconds(5), false);
            rejectingWrapper.submit(def); // 外层派发成功，内层提交被拒 → 归还许可并记 FAILED

            assertTrue(waitUntil(() -> hasStatus(ExecutionStatus.FAILED), 3000), "被拒提交应记录 FAILED");
            assertTrue(waitUntil(() -> permits(rejectingWrapper, def.taskId()) == 1, 3000),
                    "提交被拒时业务从未开始，许可必须归还，否则任务会永久 SKIPPED");
        } finally {
            rejectAfterFirst.shutdownNow();
        }
    }

    // ---------- helpers ----------

    private boolean hasStatus(ExecutionStatus status) {
        return records.stream().anyMatch(r -> r.status() == status);
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Semaphore> gateMap() throws Exception {
        return gateMap(wrapper);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Semaphore> gateMap(TaskExecutorWrapper target) throws Exception {
        Field field = TaskExecutorWrapper.class.getDeclaredField("gates");
        field.setAccessible(true);
        return (Map<String, Semaphore>) field.get(target);
    }

    private int permits(String taskId) {
        return permits(wrapper, taskId);
    }

    private static int permits(TaskExecutorWrapper target, String taskId) {
        try {
            Semaphore gate = gateMap(target).get(taskId);
            return gate == null ? -1 : gate.availablePermits();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TaskDefinition def(String taskId, Duration timeout, boolean allowConcurrent) {
        return new TaskDefinition(
                taskId, "task-" + taskId, true, "cron", "0 0 0 1 1 ?",
                "com.w3.taskscheduler.jobs.handler.TestHandler",
                timeout, 0, null, allowConcurrent, false, null, null, Map.of());
    }

    /** 第一次提交放行（真实虚拟线程执行），之后的提交一律拒绝，用于模拟停机时执行器已关闭 */
    private static final class RejectAfterFirst extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicInteger submissions = new AtomicInteger();

        RejectAfterFirst(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            if (submissions.incrementAndGet() > 1) {
                throw new RejectedExecutionException("executor closed");
            }
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
