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
 * @param trigger
 * @param cron
 * @param handler
 * @param timeout
 * @param maxRetries
 * @param retryDelay
 * @param allowConcurrent
 * @param runOnStartup 程序启动后是否立即执行一次（仅 cron 模式生效，enabled=true 时；
 *            interval 任务注册后即触发首次执行，无需配置，配置也会被忽略）
 * @param interval 固定间隔调度（PeriodicTrigger）：配置后任务按“注册（≈启动）后立即执行一次，
 *            之后每 interval 精确执行一次”调度，触发时刻 = 注册时刻 + k×interval（秒级相位保留，
 *            不对齐墙钟整分/整秒）。与 {@code cron} 二选一，互斥。
 * @param params
 */
public record TaskDefinition(
        String taskId,
        String name,
        @NotNull boolean enabled,
        String trigger,
        String cron,
        String handler,
        Duration timeout,
        int maxRetries,
        Duration retryDelay,
        Boolean allowConcurrent,
        boolean runOnStartup,
        Duration interval,
        Map<String, Object> params) {

    public TaskDefinition withTaskId(String taskId) {
        return new TaskDefinition(
                taskId,
                name,
                enabled,
                trigger,
                cron,
                handler,
                timeout,
                maxRetries,
                retryDelay,
                allowConcurrent,
                runOnStartup,
                interval,
                params
        );
    }

    public TaskDefinition withEnabled(boolean enable) {
        return new TaskDefinition(
                taskId,
                name,
                enable,
                trigger,
                cron,
                handler,
                timeout,
                maxRetries,
                retryDelay,
                allowConcurrent,
                runOnStartup,
                interval,
                params
        );
    }
}
