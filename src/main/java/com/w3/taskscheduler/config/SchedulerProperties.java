package com.w3.taskscheduler.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 绑定 scheduler.* 运行时配置（时区/线程前缀/停机超时等）
 */
@Data
@Valid
@Configuration
@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {
    @NotNull
    private ZoneId timezone = ZoneId.systemDefault();

    @NotNull
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    @Min(1)
    private int schedulerPoolSize = 2;

    private boolean allowConcurrent = false;

    @Min(1)
    private int executionHistorySize = 1000;

    private String taskConfigLocation;
}
