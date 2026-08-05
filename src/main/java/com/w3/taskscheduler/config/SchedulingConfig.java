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
        // scheduler.setClock(Clock.system(props.getTimezone()));
        return scheduler;
    }
}
