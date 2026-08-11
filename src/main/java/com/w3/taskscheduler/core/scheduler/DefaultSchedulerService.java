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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 调度服务默认实现：
 * <ul>
 * <li>启动时从 YAML 加载任务定义，并把 enabled 的任务按 cron 注册到 {@link TaskRegistry}；</li>
 * <li>提供任务的启用/禁用/手动触发/注销等运行时控制能力；</li>
 * <li>真正的任务执行统一委托给 {@link TaskExecutorWrapper}（虚拟线程 + 并发闸门 + 执行记录）。</li>
 * </ul>
 */
public class DefaultSchedulerService implements SchedulerService {
    /** 任务配置加载器：读取 YAML，校验并返回 {@link TaskDefinition} 列表 */
    private final TaskConfigLoader loader;
    /** 注册中心：维护 taskId -> ScheduledFuture 的映射，负责 cron 的注册与取消 */
    private final TaskRegistry registry;
    /** 任务执行包装：提交虚拟线程执行、并发闸门、超时/重试、生成执行记录 */
    private final TaskExecutorWrapper executorWrapper;
    /** 调度器运行期配置（时区、线程池大小、任务配置文件位置等） */
    private final SchedulerProperties props;
    /** 内存中的任务定义快照：taskId -> TaskDefinition（无论 enabled 与否都会保存） */
    private final ConcurrentHashMap<String, TaskDefinition> definitions = new ConcurrentHashMap<>();
    /** 调度器是否已启动的原子标记，保证 start()/stop() 幂等 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 调度时区，start() 时从配置初始化；注册 cron 时使用 */
    private volatile ZoneId zoneId;

    /**
     * 启动调度器（幂等）：
     * 1. CAS 保证只启动一次；
     * 2. 初始化时区；
     * 3. 加载全部任务定义到内存快照；
     * 4. 仅把 enabled 的任务注册进 {@link TaskRegistry}。
     */
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

    /**
     * 优雅停止调度器（幂等）：
     * 取消所有已注册的 cron 任务；正在执行中的任务不受影响（由虚拟线程池继续跑完）。
     */
    @Override
    public synchronized void stop() {
        if (!started.compareAndSet(true, false))
            return;
        registry.getTaskDefinitions().forEach(v -> {
            String taskId = v.taskId();
            registry.unregister(taskId);
        }); // cancel(false)
    }

    /**
     * 重读 YAML 配置，对任务定义做 diff 后增量生效（TODO：尚未实现）。
     */
    @Override
    public synchronized void reload() {

    }

    /**
     * 直接注销指定任务：取消其未来的 cron 触发，并从注册中心移除。
     */
    @Override
    public void unregisterTask(String taskId) {
        registry.unregister(taskId);
    }

    /**
     * 启用任务：为指定任务按 cron 重新注册到 {@link TaskRegistry}。
     * 幂等：已注册则直接返回；同时把内存快照中的 enabled 置为 true。
     */
    @Override
    public synchronized void enableTask(String taskId) {
        checkStarted();
        TaskDefinition def = requireDefinition(taskId);
        if (registry.isRegistered(taskId)) {
            log.info("task already enabled, taskId={}", taskId);
            return;
        }
        registry.register(def.withEnabled(true), zoneId);
        definitions.put(taskId, def.withEnabled(true));
    }

    /**
     * 禁用任务：仅取消未来的 cron 触发，不中断执行中的任务（执行走虚拟线程池）。
     * 幂等：未注册则直接返回；同时把内存快照中的 enabled 置为 false。
     */
    @Override
    public synchronized void disableTask(String taskId) {
        requireDefinition(taskId);
        if (!registry.isRegistered(taskId)) {
            return;
        }
        registry.unregister(taskId);
        definitions.computeIfPresent(taskId, (k, def) -> def.withEnabled(false));
    }

    /**
     * 手动触发一次任务，不走 cron：
     * 直接委托 {@link TaskExecutorWrapper#submit}，由它统一处理并发闸门与执行记录。
     */
    @Override
    public synchronized void triggerTask(String taskId) {
        TaskDefinition def = requireDefinition(taskId);
        executorWrapper.submit(def);
    }

    /**
     * 从内存快照中查找任务定义，不存在时抛出 {@link IllegalArgumentException}。
     */
    private TaskDefinition requireDefinition(String taskId) {
        TaskDefinition taskDefinition = definitions.getOrDefault(taskId, null);
        if (taskDefinition == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        return taskDefinition;
    }

    /**
     * 校验调度器已启动，否则抛出 {@link IllegalStateException}。
     */
    private void checkStarted() {
        if (!started.get()) {
            throw new IllegalStateException("scheduler has not been started");
        }
    }
}
