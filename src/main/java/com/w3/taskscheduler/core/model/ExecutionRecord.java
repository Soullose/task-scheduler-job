package com.w3.taskscheduler.core.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record ExecutionRecord(
        String executionId, // UUID，每次触发唯一
        String taskName, // 任务名
        String cron, // 触发时的 cron 快照
        Map<String, Object> params, // 参数快照（深拷贝，防业务修改影响后续）
        Instant triggeredAt, // 计划触发时刻
        Instant startAt, // 实际开始时刻（虚拟线程内）
        Instant endAt, // 结束时刻（成功/失败/超时）
        ExecutionStatus status,
        int attempts, // 实际尝试次数（含重试）
        String errorMessage, // 失败原因摘要（异常类名+message，栈打印进日志）
        Integer exitCode) {
    public static Builder start(TaskDefinition def) {
        return new Builder(def);
    }

    /**
     * 没开始就被并发闸门拦下，直接产出一条 SKIPPED 终态记录
     */
    public static ExecutionRecord skipped(TaskDefinition def, String reason) {
        Instant now = Instant.now();
        return new ExecutionRecord(
                UUID.randomUUID().toString(),
                def.name(),
                def.cron(),
                def.params() == null ? Map.of() : new HashMap<>(def.params()),
                now, now, now, ExecutionStatus.SKIPPED, 0, reason, null
        );
    }

    public static final class Builder {
        private final String executionId = UUID.randomUUID().toString();
        private final String taskName;
        private final String cron;
        private final Map<String, Object> params;
        private final Instant triggeredAt = Instant.now();
        private final Instant startAt = Instant.now();
        private int attempts = 0;

        private Builder(TaskDefinition def) {
            this.taskName = def.name();
            this.cron = def.cron();
            this.params = def.params() == null ? Map.of() : new HashMap<>(def.params());
        }

        public Instant triggeredAt() {
            return triggeredAt;
        }

        public void noteAttempt(int attempt) {
            this.attempts = Math.max(this.attempts, attempt);
        }

        public ExecutionRecord succeed(int attempt) {
            return finish(ExecutionStatus.SUCCESS, attempt, null);
        }

        public ExecutionRecord fail(ExecutionStatus status, String errorMessage) {
            return finish(status, attempts, errorMessage);
        }

        private ExecutionRecord finish(ExecutionStatus status, int attempt, String errorMessage) {
            Instant endAt = Instant.now();
            return new ExecutionRecord(
                    executionId, taskName, cron, params,
                    triggeredAt, startAt, endAt, status,
                    attempt, errorMessage, null
            );
        }
    }
}
