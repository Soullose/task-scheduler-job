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

    /**
     * 任务源选择（{@code scheduler.task-source}）：
     * <ul>
     * <li>{@link TaskSource#AUTO AUTO}（默认）：启动时先读 {@code t_scheduler_job} 任务表，
     *     表为空或读库失败（连接/查询异常）时自动兜底读取 {@code task-config-location} 指定的 YAML；</li>
     * <li>{@link TaskSource#YAML YAML}：强制只读 YAML，跳过 DB（开发调试 / 临时切换用）。</li>
     * </ul>
     */
    private TaskSource taskSource = TaskSource.AUTO;

    public enum TaskSource {
        /** DB 优先；空表 / 读库失败兜底 YAML（默认） */
        AUTO,
        /** 强制只读 YAML，跳过 DB */
        YAML
    }
}
