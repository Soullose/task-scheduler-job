package com.w3.taskscheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(SchedulerProperties props) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.getSchedulerPoolSize()); // 默认 2
        scheduler.setThreadNamePrefix("sched-trigger-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); // 触发任务微秒级，不等
        scheduler.setAwaitTerminationSeconds(0);
        // 取消注册（reload/停用/删除任务）时立即从 DelayedWorkQueue 摘掉已取消的条目。
        // 默认 false 时被取消的周期任务会一直留在队列里直到它「下次到期」，长周期任务（如每天 00:00 的
        // 清理任务）在反复 reload 下会累积大量已取消条目，属于隐性的堆增长。
        scheduler.setRemoveOnCancelPolicy(true);
        // scheduler.setClock(Clock.system(props.getTimezone()));
        return scheduler;
    }
}
