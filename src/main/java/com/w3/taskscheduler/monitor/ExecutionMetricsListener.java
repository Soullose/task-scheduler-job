package com.w3.taskscheduler.monitor;

import java.time.Duration;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.history.ExecutionRecordEvent;
import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionMetricsListener {
    private final SchedulerMetrics metrics;

    @EventListener
    public void onExecutionRecord(ExecutionRecordEvent event) {
        ExecutionRecord r = event.record();
        switch (r.status()) {
            case SUCCESS -> metrics.markSuccess();
            case FAILED -> metrics.markFailed();
            case TIMEOUT -> metrics.markTimeout();
            case SKIPPED -> metrics.markSkipped();
            default -> {
            } // INTERRUPTED 本期不单独计数
        }
        // 重试次数 = 实际尝试次数 - 1（SKIPPED 的 attempts=0，天然不触发）
        if (r.attempts() > 1) {
            metrics.markRetries(r.attempts() - 1);
        }
        // 耗时：SKIPPED 时 startAt==endAt 无意义，跳过
        if (r.status() != ExecutionStatus.SKIPPED && r.startAt() != null && r.endAt() != null) {
            metrics.recordDuration(r.status(), Duration.between(r.startAt(), r.endAt()).toNanos());
        }
    }
}
