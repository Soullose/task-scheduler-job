package com.w3.taskscheduler.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;

/**
 * 端到端补位测试：覆盖单元测试（mock 仓库）覆盖不到的那段真实链路——
 * 事件发布 → 监听器提交 → <b>在无事务的裸虚拟线程上直接 JPA 落库</b> → 数据库真的可见。
 *
 * <p>
 * 只断言"最终落库 + 内容正确 + 落库发生在另一条线程"，不断言耗时：
 * "发布方不被阻塞"已由 {@link JobExecutionEventWriterTests} 用可控阻塞的仓库确定性地证明，
 * 这里再用时间阈值判断只会引入 flaky。
 * </p>
 */
@SpringBootTest
class JobExecutionPersistIntegrationTests {

    private static final Duration VISIBLE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ExecutionHistoryStore historyStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String executionId;

    @AfterEach
    void cleanUp() {
        if (executionId != null) {
            jdbcTemplate.update("delete from t_job_execution where execution_id = ?", executionId);
        }
    }

    @Test
    void 事件发布后记录由异步线程落到数据库() throws Exception {
        ExecutionRecord record = record();
        executionId = record.executionId();

        AtomicReference<String> publisherThread = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        Thread businessThread = Thread.ofVirtual().name("vt-business-fake").start(() -> {
            publisherThread.set(Thread.currentThread().getName());
            historyStore.add(record); // 业务线程侧的全部成本
            published.countDown();
        });

        assertTrue(published.await(5, TimeUnit.SECONDS), "事件发布不应被落库阻塞");
        assertEquals("vt-business-fake", publisherThread.get());

        long deadline = System.nanoTime() + VISIBLE_TIMEOUT.toNanos();
        Integer rows = 0;
        while (System.nanoTime() < deadline && rows == 0) {
            rows = jdbcTemplate.queryForObject(
                    "select count(*) from t_job_execution where execution_id = ?", Integer.class, executionId
            );
            Thread.sleep(20);
        }

        assertTrue(rows > 0, "记录应在 " + VISIBLE_TIMEOUT + " 内异步落库，实际未查到");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select task_name, execution_status, attempts from t_job_execution where execution_id = ?",
                executionId
        );
        assertEquals("it-task", row.get("task_name"));
        assertEquals(ExecutionStatus.SUCCESS.name(), row.get("execution_status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());

        businessThread.join(TimeUnit.SECONDS.toMillis(5));
    }

    private static ExecutionRecord record() {
        LocalDateTime now = LocalDateTime.now();
        return new ExecutionRecord(
                "it-" + java.util.UUID.randomUUID(), "it-task", "0/2 * * * * ?", Map.of("k", "v"),
                now, now, now, ExecutionStatus.SUCCESS, 1, null, null
        );
    }
}
