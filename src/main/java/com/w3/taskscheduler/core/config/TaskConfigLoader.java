package com.w3.taskscheduler.core.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.github.f4b6a3.uuid.UuidCreator;
import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 读 YAML → List<TaskDefinition>；校验 trigger 取值、cron/interval 与模式互斥、handler 可解析
 */
@Slf4j
@AllArgsConstructor
@Component
public class TaskConfigLoader {
    private final ResourceLoader resourceLoader;

    private final SchedulerProperties schedulerProperties;

    public List<TaskDefinition> load() throws IOException {
        log.info("schedulerProperties:{}", schedulerProperties);
        String taskConfigLocation = schedulerProperties.getTaskConfigLocation();
        Resource resource = resourceLoader.getResource(taskConfigLocation);
        if (!resource.exists()) {
            throw new FileNotFoundException("文件不存在: " + taskConfigLocation);
        }
        List<TaskDefinition> defs;
        try (InputStream in = resource.getInputStream()) { // try-with-resources
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("tasks", resource);
            defs = new Binder(ConfigurationPropertySources.from(sources))
                    .bind("tasks", Bindable.listOf(TaskDefinition.class))
                    .orElseThrow(
                            () -> new IllegalArgumentException(
                                    "任务配置文件缺少 tasks 段或结构错误: " + taskConfigLocation
                            )
                    );
        }
        defs = defs.stream()
                .map(d -> d.withTaskId(UuidCreator.getTimeOrderedEpoch().toString()))
                .toList();
        defs.forEach(this::validate);
        log.info("event=config.load tasks={} location={}", defs.size(), taskConfigLocation);
        return defs;
    }

    /**
     * 单条校验（启动加载与 REST 动态注册共用）
     * <p>
     * 规则：{@code trigger} 必须为 {@code cron} 或 {@code interval}（二选一）；trigger=cron 时 cron 需为合法
     * Spring 六字段表达式且不得配置 interval/interval-mode；trigger=interval 时 interval 需为正时长、不得配置 cron，
     * interval-mode（可选）由绑定层保证取值合法；handler 不能为空。
     * </p>
     */
    public void validate(TaskDefinition def) {
        String trigger = def.trigger();
        if (!"cron".equals(trigger) && !"interval".equals(trigger)) {
            throw new IllegalArgumentException(
                    "trigger 必须为 cron 或 interval（当前: " + trigger + "）: " + def.taskId()
            );
        }
        if ("cron".equals(trigger)) {
            if (def.cron() == null || def.cron().isBlank()) {
                throw new IllegalArgumentException("trigger=cron 的任务必须配置 cron: " + def.taskId());
            }
            if (!CronExpression.isValidExpression(def.cron())) {
                throw new IllegalArgumentException(
                        "cron 非法（须为 Spring 六字段：秒 分 时 日 月 周）: " + def.cron()
                );
            }
            if (def.interval() != null || def.intervalMode() != null) {
                throw new IllegalArgumentException(
                        "trigger=cron 的任务不得配置 interval / interval-mode: " + def.taskId()
                );
            }
        } else { // trigger = interval
            if (def.interval() == null) {
                throw new IllegalArgumentException("trigger=interval 的任务必须配置 interval: " + def.taskId());
            }
            if (def.interval().isZero() || def.interval().isNegative()) {
                throw new IllegalArgumentException(
                        "interval 必须为正时长: " + def.interval()
                );
            }
            if (def.cron() != null && !def.cron().isBlank()) {
                throw new IllegalArgumentException(
                        "trigger=interval 的任务不得配置 cron: " + def.taskId()
                );
            }
        }
        if (def.handler() == null || def.handler().isBlank()) {
            throw new IllegalArgumentException("handler 不能为空");
        }
    }
}
