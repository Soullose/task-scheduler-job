package com.w3.taskscheduler.jobs.handler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.model.TaskDefinition;
import com.w3.taskscheduler.core.persistence.repository.ExecutionRecordRepository;

/**
 * {@link JobExecutionCleanupHandler} 清理逻辑测试（不依赖数据库）：
 * 分批循环直到某批不足一批才停止；保留期固定为「当前时间 - 12 小时」；每批大小固定 1000。
 */
class JobExecutionCleanupHandlerTests {

    private static final int BATCH_SIZE = 1000;

    private TaskContext ctx() {
        TaskDefinition def = new TaskDefinition(
                "task-id", "job_execution_cleanup", true, "cron", "0 0 0 * * ?",
                JobExecutionCleanupHandler.class.getName(), null, 0, null, false, false, null, null, null
        );
        return new TaskContext("exec-id", def, Instant.now());
    }

    @Test
    void loopsUntilBatchIsIncomplete() throws Exception {
        ExecutionRecordRepository repo = mock(ExecutionRecordRepository.class);
        when(repo.deleteSuccessBatchBefore(any(), anyInt())).thenReturn(1000, 1000, 12);

        new JobExecutionCleanupHandler(repo).execute(ctx());

        // 两批满批 + 一批 12 条（不足一批）→ 调用 3 次后停止
        verify(repo, times(3)).deleteSuccessBatchBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void stopsImmediatelyWhenNothingToDelete() throws Exception {
        ExecutionRecordRepository repo = mock(ExecutionRecordRepository.class);
        when(repo.deleteSuccessBatchBefore(any(), anyInt())).thenReturn(0);

        new JobExecutionCleanupHandler(repo).execute(ctx());

        verify(repo, times(1)).deleteSuccessBatchBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void cutoffIsTwelveHoursAgo() throws Exception {
        ExecutionRecordRepository repo = mock(ExecutionRecordRepository.class);
        when(repo.deleteSuccessBatchBefore(any(), anyInt())).thenReturn(0);

        Instant before = Instant.now().minus(Duration.ofHours(12));
        new JobExecutionCleanupHandler(repo).execute(ctx());
        Instant after = Instant.now().minus(Duration.ofHours(12));

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteSuccessBatchBefore(cutoff.capture(), eq(BATCH_SIZE));

        Instant actual = cutoff.getValue();
        assertTrue(!actual.isBefore(before) && !actual.isAfter(after),
                "cutoff 应为执行时刻 - 12 小时，实际: " + actual);
    }
}
