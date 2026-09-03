package com.w3.taskscheduler.core.scheduler;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.exec.TaskExecutorWrapper;
import com.w3.taskscheduler.core.model.IntervalMode;
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

        // 两种调度模式由 trigger 字段选择（TaskConfigLoader.validate 已保证一致）：
        // 1) trigger=interval → 固定间隔（PeriodicTrigger）：首次触发在注册后一个 interval
        //    （initialDelay = interval，不会注册即执行；如需启动立即执行一次请配 run-on-startup），
        //    之后按 interval 推进；推进模式由 interval-mode 决定（缺省 RATE）：
        //    RATE = fixed-rate（每次 = 上一次计划触发时刻 + interval，节奏不漂移，默认），
        //    DELAY = fixed-delay（每次 = 上一次完成时刻 + interval）。
        //    注意：业务在虚拟线程异步执行，触发 Runnable 只做提交（微秒级），PeriodicTrigger 感知的
        //    “完成”是提交完成；防止业务重叠由并发闸门（allow-concurrent=false）负责。
        // 2) trigger=cron → cron：按 Spring 六字段墙钟网格触发（秒 分 时 日 月 周）。
        Trigger scheduleTrigger;
        if ("interval".equals(taskDefinition.trigger())) {
            PeriodicTrigger periodicTrigger = new PeriodicTrigger(taskDefinition.interval());
            IntervalMode mode = taskDefinition.intervalMode() == null
                    ? IntervalMode.RATE
                    : taskDefinition.intervalMode();
            periodicTrigger.setFixedRate(mode == IntervalMode.RATE);
            // 注册后不立即执行：首个触发点 = 注册时刻 + interval（之后按 interval 续推）
            periodicTrigger.setInitialDelay(taskDefinition.interval());
            scheduleTrigger = periodicTrigger;
        } else {
            scheduleTrigger = new CronTrigger(taskDefinition.cron(), zoneId);
        }

        ScheduledFuture<?> scheduledFuture = taskScheduler.schedule(trigger, scheduleTrigger);
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
