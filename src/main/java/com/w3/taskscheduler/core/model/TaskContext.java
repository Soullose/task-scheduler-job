package com.w3.taskscheduler.core.model;

import java.time.Instant;
import java.util.Map;

import com.w3.taskscheduler.core.invoke.TaskInvoker;

/**
 * 任务执行上下文：由 {@link TaskInvoker} 反射调用 handler 的 execute(TaskContext) 时传入，
 * 携带本次执行所需的元数据与参数快照。
 *
 * @param executionId 本次执行唯一 ID（UUID 字符串）
 * @param task 当前任务定义（taskId/cron/params 等不可变元数据）
 * @param triggeredAt 计划触发时刻（手动触发或 cron 触发的时间点）
 */
public record TaskContext(
        String executionId,
        TaskDefinition task,
        Instant triggeredAt) {

    /**
     * 便捷方法：直接返回任务定义的参数 Map（可能为 null）。
     */
    public Map<String, Object> params() {
        return task.params();
    }
}
