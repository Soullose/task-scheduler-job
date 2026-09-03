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
 * @param trigger 调度模式选择：{@code cron} 或 {@code interval}（必填）
 * @param cron
 * @param handler
 * @param timeout
 * @param maxRetries
 * @param retryDelay
 * @param allowConcurrent
 * @param runOnStartup 程序启动后是否立即执行一次（默认 false；enabled=true 时生效）。
 *            cron 与 interval 任务均适用：interval 任务的周期首次触发在注册后一个 interval，
 *            配 run-on-startup 可在启动时补一次即时执行，二者不重叠。
 * @param interval 固定间隔调度（PeriodicTrigger）：配置后任务在注册后一个 interval 首次触发
 *            （不会注册即执行），之后每 interval 执行一次，推进方式见 {@link #intervalMode()}。
 *            与 {@code cron} 二选一，互斥。
 * @param intervalMode interval 的推进模式（可选）：{@link IntervalMode#RATE rate}（默认）或
 *            {@link IntervalMode#DELAY delay}，对应 PeriodicTrigger 的 fixed-rate / fixed-delay；
 *            仅当 {@code trigger=interval} 时有意义。
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
        IntervalMode intervalMode,
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
                intervalMode,
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
                intervalMode,
                params
        );
    }
}
