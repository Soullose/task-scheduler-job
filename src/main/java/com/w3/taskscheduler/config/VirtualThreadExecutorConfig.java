package com.w3.taskscheduler.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VirtualThreadExecutorConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService virtualTaskExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vt-scheduler-vt-", 0).factory()
        );
    }
}
