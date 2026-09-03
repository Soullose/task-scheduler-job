package com.w3.taskscheduler.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import com.w3.taskscheduler.core.config.TaskConfigLoader;
import com.w3.taskscheduler.core.exec.TaskExecutorWrapper;
import com.w3.taskscheduler.core.model.IntervalMode;
import com.w3.taskscheduler.core.model.TaskDefinition;

/**
 * interval（固定间隔 / PeriodicTrigger）调度模式测试：
 * <ul>
 * <li>相位语义：fixed-rate 以“上次计划触发时刻 + interval”推进，秒级相位保留，
 *     不会像 cron 那样对齐到整分整秒（对应 “20:30:30 起每 3 分钟 → 20:33:30” 场景）；</li>
 * <li>支持 cron 无法表达的任意秒级周期（如每 200 秒）；</li>
 * <li>注册后一个 interval 才首次触发（不会注册即执行，如需启动即执行用 run-on-startup）；
 *     interval-mode 缺省 rate；</li>
 * <li>interval-mode=delay（fixed-delay）按“上次完成时刻 + interval”推进；</li>
 * <li>cron 模式回归不受影响；</li>
 * <li>trigger 取值、cron/interval 与 interval-mode 的互斥/合法性校验；</li>
 * <li>YAML 绑定：interval 支持秒/分/小时等 Duration 写法，interval-mode 支持 rate/delay。</li>
 * </ul>
 */
class IntervalScheduleTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // ---------- PeriodicTrigger 相位语义：rate 与 delay ----------

    @Test
    void fixedRateNextExecutionKeepsStartPhase() {
        // 起点 20:30:30，间隔 3m：下一次必须是 20:33:30（而不是 cron 式的 20:33:00）
        PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofMinutes(3));
        trigger.setFixedRate(true); // 对应 interval-mode: rate

        Instant anchor = LocalDateTime.of(2025, 8, 31, 20, 30, 30).atZone(ZONE).toInstant();
        SimpleTriggerContext ctx = new SimpleTriggerContext(
                anchor, anchor.plusSeconds(1), anchor.plusSeconds(2)
        );

        Instant next = trigger.nextExecution(ctx);
        assertEquals(
                anchor.plus(Duration.ofMinutes(3)), next,
                "fixed-rate 以“上次计划触发时刻 + 间隔”推进，保留 :30 秒相位"
        );
    }

    @Test
    void fixedDelayNextExecutionFromCompletion() {
        // 对应 interval-mode: delay：以“上次完成时刻 + 间隔”推进
        PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofMinutes(3)); // 默认 fixed-delay

        Instant anchor = LocalDateTime.of(2025, 8, 31, 20, 30, 30).atZone(ZONE).toInstant();
        Instant completion = anchor.plusSeconds(2);
        SimpleTriggerContext ctx = new SimpleTriggerContext(anchor, anchor.plusSeconds(1), completion);

        assertEquals(
                completion.plus(Duration.ofMinutes(3)), trigger.nextExecution(ctx),
                "fixed-delay 以“上次完成时刻 + 间隔”推进"
        );
    }

    @Test
    void arbitraryIntervalNotExpressibleByCron() {
        // 每 200 秒不是 60 的整倍数，cron 的“分”字段表达不了这种秒级周期
        PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofSeconds(200));
        trigger.setFixedRate(true);

        Instant anchor = Instant.parse("2025-08-31T12:30:30Z");
        SimpleTriggerContext ctx = new SimpleTriggerContext(
                anchor, anchor.plusSeconds(1), anchor.plusSeconds(2)
        );

        assertEquals(anchor.plus(Duration.ofSeconds(200)), trigger.nextExecution(ctx));
    }

    @Test
    void periodicTriggerWithoutInitialDelayFiresImmediately() {
        // PeriodicTrigger 缺省 initialDelay=0：空上下文（刚注册）时首次触发 ≈ 当前时刻——
        // 这正是 TaskRegistry 需要显式 setInitialDelay(interval)，以避免 interval 任务“注册即执行”的原因
        PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofSeconds(200));
        trigger.setFixedRate(true);

        Instant before = Instant.now();
        Instant next = trigger.nextExecution(new SimpleTriggerContext());
        Instant after = Instant.now();

        assertFalse(next.isBefore(before.minusMillis(50)));
        assertFalse(next.isAfter(after.plusMillis(50)));
    }

    // ---------- 注册中心集成：interval 任务按固定间隔触发 ----------

    @Test
    void intervalTaskFirstFiresAfterIntervalThenOnCadence() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.afterPropertiesSet();
        try {
            List<Instant> fireTimes = Collections.synchronizedList(new ArrayList<>());
            TaskExecutorWrapper countingWrapper = new TaskExecutorWrapper(null, null, null) {
                @Override
                public void submit(TaskDefinition def) {
                    fireTimes.add(Instant.now());
                }
            };
            TaskRegistry registry = new TaskRegistry(scheduler, countingWrapper);
            long registeredAt = System.currentTimeMillis();
            // interval-mode 缺省 → rate；首次触发应在注册后一个 interval（300ms），而非注册即执行
            registry.register(intervalDef(Duration.ofMillis(300), null), ZONE);

            Thread.sleep(1800);

            synchronized (fireTimes) {
                assertFalse(fireTimes.isEmpty(), "interval 任务应在注册一个 interval 后触发");
                long firstDelta = fireTimes.get(0).toEpochMilli() - registeredAt;
                assertTrue(
                        firstDelta >= 150,
                        "interval 任务不应注册即执行，首次触发约在注册后 interval=300ms, firstDelta=" + firstDelta + "ms"
                );
                assertTrue(
                        fireTimes.size() >= 3,
                        "300ms interval 在 1.8s 内应至少触发 3 次, 实际 " + fireTimes.size()
                );
                // 相位锚定：多次触发应分布在 ~300ms 节拍上，而不是挤在同一毫秒
                long span = fireTimes.get(fireTimes.size() - 1).toEpochMilli()
                        - fireTimes.get(0).toEpochMilli();
                assertTrue(span >= 600, "触发应分布在 300ms 节拍上, span=" + span + "ms");
            }
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void cronTaskStillFiresOnWallClock() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.afterPropertiesSet();
        try {
            AtomicInteger fires = new AtomicInteger();
            TaskExecutorWrapper countingWrapper = new TaskExecutorWrapper(null, null, null) {
                @Override
                public void submit(TaskDefinition def) {
                    fires.incrementAndGet();
                }
            };
            TaskRegistry registry = new TaskRegistry(scheduler, countingWrapper);
            // 每秒触发（秒字段墙钟网格）——cron 模式回归
            registry.register(cronDef("* * * * * ?"), ZONE);

            Thread.sleep(3300);
            assertTrue(fires.get() >= 2, "每秒 cron 在 3.3s 内应至少触发 2 次, 实际 " + fires.get());
        } finally {
            scheduler.destroy();
        }
    }

    // ---------- 配置校验：trigger 与 cron/interval/interval-mode 的规则 ----------

    @Test
    void validateTriggerAndModeRules() {
        TaskConfigLoader loader = new TaskConfigLoader(null, null);

        // 合法
        assertDoesNotThrow(() -> loader.validate(cronDef("0 0/3 * * * ?")));
        assertDoesNotThrow(() -> loader.validate(intervalDef(Duration.ofSeconds(200), null)));
        assertDoesNotThrow(() -> loader.validate(intervalDef(Duration.ofSeconds(200), IntervalMode.RATE)));
        assertDoesNotThrow(() -> loader.validate(intervalDef(Duration.ofSeconds(200), IntervalMode.DELAY)));

        // trigger 缺失/非法
        assertThrows(IllegalArgumentException.class, () -> loader.validate(def(null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> loader.validate(def("foo", null, null, null)));

        // trigger=interval 却缺 interval / 带了 cron
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(def("interval", null, null, null))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(def("interval", "* * * * * ?", Duration.ofSeconds(200), null))
        );
        // interval 非正
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(intervalDef(Duration.ZERO, null))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(intervalDef(Duration.ofSeconds(-1), null))
        );

        // trigger=cron 却缺 cron / 带了 interval / interval-mode / cron 非法
        assertThrows(IllegalArgumentException.class, () -> loader.validate(def("cron", null, null, null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(def("cron", "0 0/3 * * * ?", Duration.ofSeconds(200), null))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(def("cron", "0 0/3 * * * ?", null, IntervalMode.RATE))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.validate(cronDef("not-a-cron"))
        );
    }

    // ---------- YAML 绑定：interval 支持秒/分/小时，interval-mode 支持 rate/delay ----------

    @Test
    void yamlBindingForDurationUnitsAndIntervalMode() throws Exception {
        // 与 TaskConfigLoader 相同的绑定路径（YamlPropertySourceLoader + Binder）
        String yaml = """
                tasks:
                  - name: "hourly_task"
                    enabled: true
                    trigger: interval
                    interval: 2h
                    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
                  - name: "iso_delay_task"
                    enabled: true
                    trigger: interval
                    interval: PT1H
                    interval-mode: delay
                    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
                  - name: "minute_rate_task"
                    enabled: true
                    trigger: interval
                    interval: 90m
                    interval-mode: rate
                    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
                  - name: "second_task"
                    enabled: true
                    trigger: interval
                    interval: 200s
                    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
                  - name: "cron_task"
                    enabled: true
                    trigger: cron
                    cron: "0/5 * * * * ?"
                    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
                """;
        Resource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("tasks", resource);
        List<TaskDefinition> defs = new Binder(ConfigurationPropertySources.from(sources))
                .bind("tasks", Bindable.listOf(TaskDefinition.class))
                .orElseThrow(() -> new IllegalStateException("tasks 段绑定失败"));

        assertEquals(5, defs.size());
        assertEquals(Duration.ofHours(2), defs.get(0).interval(), "2h 应绑定为 2 小时");
        assertEquals(Duration.ofHours(1), defs.get(1).interval(), "PT1H（ISO-8601）应绑定为 1 小时");
        assertEquals(IntervalMode.DELAY, defs.get(1).intervalMode(), "interval-mode: delay 应绑定为 DELAY");
        assertEquals(Duration.ofMinutes(90), defs.get(2).interval(), "90m 应绑定为 90 分钟");
        assertEquals(IntervalMode.RATE, defs.get(2).intervalMode(), "interval-mode: rate 应绑定为 RATE");
        assertEquals(Duration.ofSeconds(200), defs.get(3).interval(), "200s 应绑定为 200 秒");
        assertEquals("cron", defs.get(4).trigger(), "trigger: cron 应绑定为 cron");
        assertEquals(null, defs.get(3).intervalMode(), "未配 interval-mode 时应为 null（注册时按 rate 处理）");

        // 绑定结果均能通过配置校验
        TaskConfigLoader loader = new TaskConfigLoader(null, null);
        defs.forEach(def -> assertDoesNotThrow(() -> loader.validate(def)));
    }

    // ---------- helper ----------

    private static TaskDefinition def(String trigger, String cron, Duration interval, IntervalMode mode) {
        return new TaskDefinition(
                "test-task", "测试任务", true, trigger, cron, "com.w3.taskscheduler.jobs.handler.TestHandler",
                Duration.ofSeconds(60), 0, null, false, false, interval, mode, null
        );
    }

    private static TaskDefinition cronDef(String cron) {
        return def("cron", cron, null, null);
    }

    private static TaskDefinition intervalDef(Duration interval, IntervalMode mode) {
        return def("interval", null, interval, mode);
    }
}
