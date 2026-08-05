package com.w3.taskscheduler.core.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 读 YAML → List<TaskDefinition>；校验 taskId 唯一、cron 合法、handler 可解析
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
                .map(d -> d.withTaskId(UUID.randomUUID().toString()))
                .toList();
        defs.forEach(this::validate);
        log.info("event=config.load tasks={} location={}", defs.size(), taskConfigLocation);
        return defs;
    }

    /**
     * 单条校验（启动加载与 REST 动态注册共用）
     */
    public void validate(TaskDefinition def) {
        log.info("cron:{}", def.cron());
        if (!CronExpression.isValidExpression(def.cron())) {
            throw new IllegalArgumentException("cron 非法（Spring 6 字段，不支持 ?）: " + def.cron());
        }
        if (def.handler() == null || def.handler().isBlank()) {
            throw new IllegalArgumentException("handler 不能为空");
        }
    }
}
