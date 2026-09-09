package com.w3.taskscheduler.core.scheduler;

public interface SchedulerService {
    void start(); // 幂等

    void stop(); // 幂等，优雅停机

    void reload(); // 按任务源重读（auto: DB，空表/读库失败兜底 YAML；yaml: 强制 YAML），diff 增量生效，无变化零改动

    void unregisterTask(String taskId);

    /** 内存态启用（不写库）：为指定任务按 trigger 重新注册；持久化启停请走 admin REST（写 DB + reload） */
    void enableTask(String taskId);

    /** 内存态禁用（不写库）：仅取消未来触发，不中断执行中的任务；持久化启停请走 admin REST（写 DB + reload） */
    void disableTask(String taskId);

    void triggerTask(String taskId); // 手动触发一次，不走 cron（不写库）
}
