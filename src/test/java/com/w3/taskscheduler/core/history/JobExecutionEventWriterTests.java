package com.w3.taskscheduler.core.history;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;
import com.w3.taskscheduler.core.persistence.entity.JobExecutionPO;
import com.w3.taskscheduler.core.persistence.repository.ExecutionRecordRepository;

/**
 * {@link JobExecutionEventWriter} 测试：监听器被同步调用时，真正执行 save 的必须是异步虚拟线程；
 * 数据库被阻塞或抛异常都不允许影响事件发布方（即执行业务的那条线程）。
 */
class JobExecutionEventWriterTests {

    private ExecutionRecordRepository repository;
    private ExecutionPersistExecutor persistExecutor;
    private JobExecutionEventWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(ExecutionRecordRepository.class);
        persistExecutor = new ExecutionPersistExecutor();
        writer = new JobExecutionEventWriter(repository, persistExecutor);
    }

    @AfterEach
    void tearDown() {
        persistExecutor.drain(Duration.ofSeconds(2));
    }

    @Test
    void 落库被阻塞时事件发布方立即返回() throws Exception {
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> saveThread = new AtomicReference<>();
        when(repository.save(any(JobExecutionPO.class))).thenAnswer(invocation -> {
            saveThread.set(Thread.currentThread());
            saveStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return invocation.getArgument(0);
        });

        long begin = System.nanoTime();
        writer.onExecutionRecord(new ExecutionRecordEvent(record()));
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs < 200, "发布方不应等待数据库 I/O，实际耗时 " + elapsedMs + "ms");
        assertTrue(saveStarted.await(2, TimeUnit.SECONDS), "落库应在异步线程上启动");
        assertTrue(saveThread.get().isVirtual(), "落库应运行在虚拟线程上，实际为：" + saveThread.get());
        release.countDown();
    }

    @Test
    void 落库异常不回传到发布方() throws Exception {
        when(repository.save(any(JobExecutionPO.class))).thenThrow(new IllegalStateException("模拟数据库不可用"));

        assertDoesNotThrow(
                () -> writer.onExecutionRecord(new ExecutionRecordEvent(record())),
                "落库异常不得回传到发布方线程"
        );

        awaitUntilFailedOnce();
        assertEquals(1, persistExecutor.failed(), "落库失败应被计数以便观测");
    }

    @Test
    void 落库动作最终会把记录写库() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        when(repository.save(any(JobExecutionPO.class))).thenAnswer(invocation -> {
            saved.countDown();
            return invocation.getArgument(0);
        });

        writer.onExecutionRecord(new ExecutionRecordEvent(record()));

        assertTrue(saved.await(2, TimeUnit.SECONDS), "记录最终应写入数据库");
        verify(repository).save(any(JobExecutionPO.class));
        assertEquals(0, persistExecutor.failed(), "正常落库不应计入失败数");
    }

    private void awaitUntilFailedOnce() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline && persistExecutor.failed() == 0) {
            Thread.sleep(10);
        }
    }

    private static ExecutionRecord record() {
        LocalDateTime now = LocalDateTime.now();
        return new ExecutionRecord(
                "exec-1", "t1", "0/2 * * * * ?", Map.of("k", "v"),
                now, now, now, ExecutionStatus.SUCCESS, 1, null, null
        );
    }
}
