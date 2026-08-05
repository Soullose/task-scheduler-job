package com.w3.taskscheduler.core.scheduler;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.exec.TaskExecutorWrapper;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.RequiredArgsConstructor;

/**
 * 注册中心：taskId → ScheduledFuture<?> 句柄，register/unregister
 */
@Component
@RequiredArgsConstructor
public class TaskRegistry {
    private final ThreadPoolTaskScheduler taskScheduler;
    private final TaskExecutorWrapper taskExecutorWrapper;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskDefinition> definitions = new ConcurrentHashMap<>();

    /// 注册
    public void register(TaskDefinition taskDefinition, ZoneId zoneId) {
        Runnable trigger = () -> taskExecutorWrapper.submit(taskDefinition);
        CronTrigger cronTrigger = new CronTrigger(taskDefinition.cron(), zoneId);

        ScheduledFuture<?> scheduledFuture = taskScheduler.schedule(trigger, cronTrigger);
        futures.put(taskDefinition.taskId(), scheduledFuture);
        definitions.put(taskDefinition.taskId(), taskDefinition);
    }

    /// 取消注册
    public void unregister(String taskId) {
        ScheduledFuture<?> scheduledFuture = futures.remove(taskId);
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        definitions.remove(taskId);
    }

    /// 是否已经注册
    public boolean isRegistered(String taskId) {
        return futures.containsKey(taskId);
    }

    /// 获取所有的任务信息
    public List<TaskDefinition> getTaskDefinitions() {
        return List.copyOf(definitions.values());
    }
}
