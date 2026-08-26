package com.w3.taskscheduler.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.w3.taskscheduler.monitor.CountingExecutorService;

@Configuration
public class VirtualThreadExecutorConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService virtualTaskExecutor() {
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vt-scheduler-vt-", 0).factory()
        );
        return new CountingExecutorService(delegate);
    }
}
