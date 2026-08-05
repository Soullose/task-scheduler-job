package com.w3.taskscheduler.core.scheduler;

public interface SchedulerService {
    void start(); // 幂等

    void stop(); // 幂等，优雅停机

    void reload(); // 重读 YAML，diff 增量生效

    void unregisterTask(String taskId);

    void enableTask(String taskId);

    void disableTask(String taskId); // 仅取消未来触发，不中断执行中的任务

    void triggerTask(String taskId); // 手动触发一次，不走 cron
}
