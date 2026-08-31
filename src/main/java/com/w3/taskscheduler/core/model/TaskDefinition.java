package com.w3.taskscheduler.core.model;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * 单条任务的不可变元数据（taskId/cron/handler/enabled/timeout/...）
 *
 * @param taskId
 * @param name
 * @param enabled
 * @param cron
 * @param handler
 * @param timeout
 * @param maxRetries
 * @param retryDelay
 * @param allowConcurrent
 * @param runOnStartup 程序启动后是否立即执行一次（不依赖 cron，仅当 enabled=true 时生效）
 * @param params
 */
public record TaskDefinition(
        String taskId,
        String name,
        @NotNull boolean enabled,
        String cron,
        String handler,
        Duration timeout,
        int maxRetries,
        Duration retryDelay,
        Boolean allowConcurrent,
        boolean runOnStartup,
        Map<String, Object> params) {

    public TaskDefinition withTaskId(String taskId) {
        return new TaskDefinition(
                taskId,
                name,
                enabled,
                cron,
                handler,
                timeout,
                maxRetries,
                retryDelay,
                allowConcurrent,
                runOnStartup,
                params
        );
    }

    public TaskDefinition withEnabled(boolean enable) {
        return new TaskDefinition(
                taskId,
                name,
                enable,
                cron,
                handler,
                timeout,
                maxRetries,
                retryDelay,
                allowConcurrent,
                runOnStartup,
                params
        );
    }
}
