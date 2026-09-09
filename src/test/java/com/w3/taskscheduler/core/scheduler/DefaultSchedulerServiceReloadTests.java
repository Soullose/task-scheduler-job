package com.w3.taskscheduler.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.core.config.TaskConfigSource;
import com.w3.taskscheduler.core.exec.TaskExecutorWrapper;
import com.w3.taskscheduler.core.model.TaskDefinition;

/**
 * {@link DefaultSchedulerService#reload()} 全量快照 diff 收敛测试：
 * 无变化零改动；新增注册/消失注销/调度字段变注销重注册；仅 run_on_startup/name 变化不重排；
 * reload 到空集全注销（空表→YAML 兜底属于 TaskConfigSource 职责，收敛引擎只认目标任务列表）。
 */
class DefaultSchedulerServiceReloadTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private ThreadPoolTaskScheduler taskScheduler;
    private StubSource source;
    private CountingRegistry registry;
    private SchedulerProperties props;
    private DefaultSchedulerService service;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.afterPropertiesSet();
        TaskExecutorWrapper noopExecutor = new TaskExecutorWrapper(null, null, null) {
            @Override
            public void submit(TaskDefinition def) {
                // 不真正执行
            }
        };
        source = new StubSource();
        registry = new CountingRegistry(taskScheduler, noopExecutor);
        props = new SchedulerProperties();
        props.setTimezone(ZONE);
        service = new DefaultSchedulerService(source, registry, noopExecutor, props);
    }

    @AfterEach
    void tearDown() {
        taskScheduler.destroy();
    }

    // ---------- 无变化：reload 零改动 ----------

    @Test
    void reloadWithoutChangeIsNoOp() {
        source.setTarget(List.of(cronDef("a", true, "0 0 0 1 1 ?"), cronDef("b", true, "0 0 0 1 1 ?")));
        service.start();
        int registeredBefore = registry.registerCalls;
        assertEquals(2, registeredBefore);

        service.reload();

        assertEquals(registeredBefore, registry.registerCalls, "DB 无变化不应新增注册");
        assertEquals(0, registry.unregisterCalls, "DB 无变化不应注销");
        assertTrue(registry.isRegistered("a"));
        assertTrue(registry.isRegistered("b"));
    }

    // ---------- 增量生效：新增注册 / 消失注销 / 字段变重注册 / 启停切换 ----------

    @Test
    void reloadAppliesAddRemoveUpdateEnable() {
        source.setTarget(List.of(
                cronDef("a", true, "0 0 0 1 1 ?"),
                cronDef("b", true, "0 0 0 1 1 ?"),
                cronDef("c", false, "0 0 0 1 1 ?"))); // 未启用，不应注册
        service.start();
        assertEquals(2, registry.registerCalls);
        assertTrue(registry.isRegistered("a"));
        assertTrue(registry.isRegistered("b"));
        assertFalse(registry.isRegistered("c"));

        // b 消失；a 改了 cron（重注册）；c 改为启用（注册）；新增 d
        source.setTarget(List.of(
                cronDef("a", true, "0 0 0 2 1 ?"),
                cronDef("c", true, "0 0 0 1 1 ?"),
                cronDef("d", true, "0 0 0 1 1 ?")));

        service.reload();

        assertEquals(2, registry.unregisterCalls, "应注销 b 与重注册前的 a");
        assertEquals(2 + 3, registry.registerCalls, "重注册 a + 新启用 c + 新增 d");
        assertFalse(registry.isRegistered("b"), "b 消失应注销");
        assertTrue(registry.isRegistered("c"), "c 启用应注册");
        assertTrue(registry.isRegistered("d"), "d 新增应注册");
        Map<String, TaskDefinition> registered = registry.getTaskDefinitions().stream()
                .collect(Collectors.toMap(TaskDefinition::taskId, d -> d));
        assertEquals("0 0 0 2 1 ?", registered.get("a").cron(), "a 应按新 cron 重注册");
    }

    // ---------- 仅 run_on_startup / name 变化：不触发重排 ----------

    @Test
    void nameOrRunOnStartupOnlyChangeDoesNotReschedule() {
        source.setTarget(List.of(cronDef("e", true, "0 0 0 1 1 ?")));
        service.start();
        assertEquals(1, registry.registerCalls);

        TaskDefinition renamed = new TaskDefinition(
                "e", "新名字", true, "cron", "0 0 0 1 1 ?", "com.w3.taskscheduler.jobs.handler.TestHandler",
                Duration.ofSeconds(60), 0, null, null, true /*runOnStartup 变化*/, null, null, null);
        source.setTarget(List.of(renamed));

        service.reload();

        assertEquals(1, registry.registerCalls, "仅 name/run_on_startup 变化不应重注册");
        assertEquals(0, registry.unregisterCalls);
    }

    // ---------- reload 到空集：全部注销（引擎职责；空表→YAML 兜底在上游 TaskConfigSource） ----------

    @Test
    void reloadToEmptySetUnregistersAll() {
        source.setTarget(List.of(cronDef("a", true, "0 0 0 1 1 ?"), cronDef("b", true, "0 0 0 1 1 ?")));
        service.start();
        assertEquals(2, registry.registerCalls);

        source.setTarget(List.of());
        service.reload();

        assertEquals(2, registry.unregisterCalls);
        assertFalse(registry.isRegistered("a"));
        assertFalse(registry.isRegistered("b"));
    }

    // ---------- helpers ----------

    private static TaskDefinition cronDef(String taskId, boolean enabled, String cron) {
        return new TaskDefinition(
                taskId, "task-" + taskId, enabled, "cron", cron, "com.w3.taskscheduler.jobs.handler.TestHandler",
                Duration.ofSeconds(60), 0, null, null, false, null, null, null);
    }

    /** 任务源桩：可切换返回值模拟 DB/YAML 源变化 */
    private static class StubSource extends TaskConfigSource {
        private List<TaskDefinition> target = List.of();

        StubSource() {
            super(null, null, null);
        }

        void setTarget(List<TaskDefinition> target) {
            this.target = target;
        }

        @Override
        public List<TaskDefinition> loadStartupTasks() throws IOException {
            return target;
        }
    }

    /** 计数注册/注销的注册中心 */
    private static class CountingRegistry extends TaskRegistry {
        private int registerCalls;
        private int unregisterCalls;

        CountingRegistry(ThreadPoolTaskScheduler scheduler, TaskExecutorWrapper wrapper) {
            super(scheduler, wrapper);
        }

        @Override
        public void register(TaskDefinition taskDefinition, ZoneId zoneId) {
            registerCalls++;
            super.register(taskDefinition, zoneId);
        }

        @Override
        public void unregister(String taskId) {
            unregisterCalls++;
            super.unregister(taskId);
        }
    }
}
