package com.w3.taskscheduler.core.scheduler;

import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.core.config.TaskConfigLoader;
import com.w3.taskscheduler.core.exec.TaskExecutorWrapper;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DefaultSchedulerService implements SchedulerService {
    private final TaskConfigLoader loader;
    private final TaskRegistry registry;
    private final TaskExecutorWrapper executorWrapper;
    private final SchedulerProperties props;
    private final ConcurrentHashMap<String, TaskDefinition> definitions = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ZoneId zoneId;

    @Override
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        zoneId = props.getTimezone();
        try {
            List<TaskDefinition> taskDefinitions = loader.load();
            taskDefinitions.forEach(definition -> definitions.put(definition.taskId(), definition));

            taskDefinitions.stream().filter(def -> def.enabled())
                    .forEach(d -> registry.register(d, zoneId));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!started.compareAndSet(true, false))
            return;
        registry.getTaskDefinitions().forEach(v -> {
            String taskId = v.taskId();
            registry.unregister(taskId);
        }); // cancel(false)
    }

    @Override
    public synchronized void reload() {

    }

    @Override
    public void unregisterTask(String taskId) {

    }

    @Override
    public void enableTask(String taskId) {

    }

    @Override
    public void disableTask(String taskId) {

    }

    @Override
    public void triggerTask(String taskId) {

    }
}
