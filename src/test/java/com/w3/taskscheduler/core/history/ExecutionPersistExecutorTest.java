package com.w3.taskscheduler.core.history;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ExecutionPersistExecutor} 行为测试：异步落库的三条底线——
 * 不占用调用方线程、异常只计数不上抛、停机 best-effort 收尾且不卡死停机。
 */
class ExecutionPersistExecutorTest {

    private final ExecutionPersistExecutor executor = new ExecutionPersistExecutor();

    @AfterEach
    void tearDown() {
        executor.drain(Duration.ofSeconds(2));
    }

    @Test
    void 落库跑在虚拟线程上() throws Exception {
        AtomicReference<Thread> runner = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.submit(() -> {
            runner.set(Thread.currentThread());
            done.countDown();
        });

        assertTrue(done.await(2, TimeUnit.SECONDS), "落库任务应在 2s 内执行");
        assertTrue(runner.get().isVirtual(), "落库应运行在虚拟线程上，实际为：" + runner.get());
    }

    @Test
    void 落库阻塞时提交方不被拖住() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(blockingTask(started, release));

        assertTrue(started.await(2, TimeUnit.SECONDS), "落库任务应已开始执行（此刻仍阻塞在 I/O 上）");

        long begin = System.nanoTime();
        executor.submit(() -> {
        });
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs < 100, "提交落库应只付出入队成本，实际耗时 " + elapsedMs + "ms");
        release.countDown();
    }

    @Test
    void 落库失败只计数不上抛() throws Exception {
        assertDoesNotThrow(
                () -> executor.submit(() -> {
                    throw new IllegalStateException("模拟数据库不可用");
                }),
                "落库异常不得回传到调用方线程"
        );

        awaitUntil(() -> executor.failed() == 1, Duration.ofSeconds(2));
        assertEquals(1, executor.failed());
    }

    @Test
    void 停机后再提交被安全丢弃() {
        executor.drain(Duration.ofSeconds(1));

        assertDoesNotThrow(
                () -> executor.submit(() -> {
                    throw new AssertionError("停机后不应再执行落库任务");
                }),
                "停机窗口内提交也不得抛异常影响业务线程"
        );
        assertEquals(1, executor.failed(), "停机窗口内被丢弃的记录也应计入失败数");
    }

    @Test
    void 停机收尾会等待在途落库写完() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();

        executor.submit(() -> {
            started.countDown();
            sleep(150);
            written.set(true);
        });
        assertTrue(started.await(2, TimeUnit.SECONDS), "落库任务应已开始执行");

        executor.drain(Duration.ofSeconds(5));

        assertTrue(written.get(), "drain 应等待在途落库写完");
    }

    @Test
    void 停机收尾超时则放弃且不阻塞停机() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();
        executor.submit(blockingTask(started, release, written));

        assertTrue(started.await(2, TimeUnit.SECONDS), "落库任务应已开始执行");

        long begin = System.nanoTime();
        executor.drain(Duration.ofMillis(200));
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs < 2_000, "drain 超时应快速放弃，实际耗时 " + elapsedMs + "ms");
        assertFalse(written.get(), "被强制中断的落库不应再写入");
        release.countDown();
    }

    private static Runnable blockingTask(CountDownLatch started, CountDownLatch release) {
        return blockingTask(started, release, new AtomicBoolean());
    }

    private static Runnable blockingTask(CountDownLatch started, CountDownLatch release, AtomicBoolean written) {
        return () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            written.set(true);
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("等待条件超时：" + timeout);
    }
}
