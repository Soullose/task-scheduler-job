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
                params
        );
    }
}
