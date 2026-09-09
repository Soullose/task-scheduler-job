package com.w3.taskscheduler.core.scheduler;

import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.core.config.TaskConfigSource;
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
 * <li>启动与 {@code reload()} 均经 {@link TaskConfigSource} 加载任务定义：auto 模式优先从 t_scheduler_job
 *     读取（表为空/读库失败自动兜底 YAML），yaml 模式强制读 YAML，并把 enabled 的任务按 trigger
 *     注册到 {@link TaskRegistry}；</li>
 * <li>{@code reload()} 以全量快照 diff 增量生效：DB 未变则零改动，新增注册、消失注销、调度字段变化
 *     注销重注册（仅内存态的服务层 enable/disable/trigger/unregister 不写库；持久化增删改/启停
 *     由 admin REST 路径写 DB 后触发 reload）；</li>
 * <li>真正的任务执行统一委托给 {@link TaskExecutorWrapper}（虚拟线程 + 并发闸门 + 执行记录）。</li>
 * </ul>
 */
public class DefaultSchedulerService implements SchedulerService {
    /** 启动任务源：auto(DB 优先 + YAML 兜底) / yaml(强制)，读取并校验返回 {@link TaskDefinition} 列表 */
    private final TaskConfigSource taskSource;
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
     * 4. 仅把 enabled 的任务注册进 {@link TaskRegistry}；
     * 5. 对 enabled 且 runOnStartup=true 的任务各立即执行一次（异步，不阻塞启动）。
     */
    @Override
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        zoneId = props.getTimezone();
        try {
            List<TaskDefinition> taskDefinitions = taskSource.loadStartupTasks();
            taskDefinitions.forEach(definition -> definitions.put(definition.taskId(), definition));

            taskDefinitions.stream().filter(def -> def.enabled())
                    .forEach(d -> registry.register(d, zoneId));

            // 启动后立即执行一次：对 enabled 且 runOnStartup=true 的任务（cron 与 interval 均适用），
            // 直接提交到虚拟线程执行（走并发闸门/超时/重试/执行记录，不阻塞启动流程）。
            // 说明：interval 任务的周期首次触发在注册后一个 interval，与 run-on-startup 的即时执行不重叠。
            taskDefinitions.stream()
                    .filter(def -> def.enabled() && def.runOnStartup())
                    .forEach(d -> {
                        log.info("event=startup.run taskId={} name={} trigger={} (run-on-startup)", d.taskId(), d.name(), d.trigger());
                        executorWrapper.submit(d);
                    });

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
     * 重读任务源（auto：t_scheduler_job，空表/读库失败兜底 YAML；yaml：强制 YAML），
     * 与当前调度做全量快照 diff 后增量生效：
     * <ul>
     * <li>源没有变化 → 调度器零改动（no-op，不会盲目全量注销重注册）；</li>
     * <li>enabled 新增 → 注册；消失 → 注销；调度相关字段变化 → 注销后重注册（按最新定义）；</li>
     * <li>{@code run_on_startup} 只在进程启动时补跑：reload 不补跑，且仅它（或 name）变化不触发重注册；</li>
     * <li>源数据不合法 / 读源失败（非兜底场景）→ 抛异常快速失败，内存快照与注册中心保持原状，不部分生效。</li>
     * </ul>
     */
    @Override
    public synchronized void reload() {
        checkStarted();
        try {
            List<TaskDefinition> target = taskSource.loadStartupTasks();
            applySnapshot(target);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 把内存快照与注册中心收敛到目标任务列表（幂等：无变化零改动）。
     * 先读后写：任何读取/映射/校验异常都会在改动前抛出，保证调度状态不部分生效。
     */
    private void applySnapshot(List<TaskDefinition> target) {
        // 1) 内存定义快照整体替换为目标集（含 enabled=false 的任务），保证 triggerTask/服务层查询与源一致
        definitions.clear();
        target.forEach(d -> definitions.put(d.taskId(), d));

        // 2) 注册中心按 enabled 集做 diff
        Map<String, TaskDefinition> current = registry.getTaskDefinitions().stream()
                .collect(Collectors.toMap(TaskDefinition::taskId, Function.identity()));
        Map<String, TaskDefinition> desired = target.stream()
                .filter(TaskDefinition::enabled)
                .collect(Collectors.toMap(TaskDefinition::taskId, Function.identity()));

        // 2a) 取消：已注册但源中不再 enabled / 调度字段发生变化的任务（变化者注销后用新定义重注册）
        current.forEach((taskId, old) -> {
            TaskDefinition next = desired.get(taskId);
            if (next == null) {
                log.info("event=reload.remove taskId={} name={}", taskId, old.name());
                registry.unregister(taskId);
            } else if (!sameScheduling(old, next)) {
                log.info("event=reload.update taskId={} name={} trigger={} (re-register)", taskId, next.name(), next.trigger());
                registry.unregister(taskId);
                registry.register(next, zoneId);
            }
        });
        // 2b) 新增：源中 enabled 且当前未注册
        desired.forEach((taskId, def) -> {
            if (!current.containsKey(taskId)) {
                log.info("event=reload.add taskId={} name={} trigger={}", taskId, def.name(), def.trigger());
                registry.register(def, zoneId);
            }
        });
        log.info("event=reload.done definitions={} registered={}", definitions.size(), desired.size());
    }

    /**
     * 调度语义是否等价（决定是否需注销重注册）。
     * 只比较会影响未来触发与执行行为的字段；{@code name}/{@code run_on_startup} 不参与——
     * name 仅展示，run_on_startup 仅进程启动时生效。
     */
    private static boolean sameScheduling(TaskDefinition a, TaskDefinition b) {
        return Objects.equals(a.trigger(), b.trigger())
                && Objects.equals(a.cron(), b.cron())
                && Objects.equals(a.handler(), b.handler())
                && Objects.equals(a.timeout(), b.timeout())
                && a.maxRetries() == b.maxRetries()
                && Objects.equals(a.retryDelay(), b.retryDelay())
                && Objects.equals(a.allowConcurrent(), b.allowConcurrent())
                && Objects.equals(a.interval(), b.interval())
                && a.intervalMode() == b.intervalMode()
                && Objects.equals(a.params(), b.params());
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
