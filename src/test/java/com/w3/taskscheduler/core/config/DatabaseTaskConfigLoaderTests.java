package com.w3.taskscheduler.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.w3.taskscheduler.core.model.IntervalMode;
import com.w3.taskscheduler.core.model.TaskDefinition;
import com.w3.taskscheduler.core.persistence.entity.SchedulerJobPO;

/**
 * {@link DatabaseTaskConfigLoader} 库行 → TaskDefinition 映射测试：
 * trigger 宽松推断、时长/interval-mode/run-on-startup 缺省、params JSON 解析、
 * 数据不合法快速失败（与 YAML 校验规则一致）。
 */
class DatabaseTaskConfigLoaderTests {

    private DatabaseTaskConfigLoader loader;

    @BeforeEach
    void setUp() {
        // map() 不依赖仓库，repo 传 null 即可
        loader = new DatabaseTaskConfigLoader(null, new TaskConfigLoader(null, null));
    }

    // ---------- cron 推断 + 全字段映射 ----------

    @Test
    void cronRowWithoutTriggerInferredAndFullFieldsMapped() {
        SchedulerJobPO po = new SchedulerJobPO();
        po.setId("id-cron-1");
        po.setName("db_sync");
        po.setEnable(true);
        po.setCron("0/10 * * * * ?");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");
        po.setDescription("仅展示用描述");
        po.setTimeOut("60s");
        po.setMaxRetries(3);
        po.setRetryDelay("5s");
        po.setAllowConcurrent(false);
        po.setParams("{\"source\":\"api\",\"batch-size\":100}");

        TaskDefinition def = loader.map(po);

        assertEquals("id-cron-1", def.taskId());
        assertEquals("db_sync", def.name());
        assertTrue(def.enabled());
        assertEquals("cron", def.trigger(), "trigger 为空、cron 有值时应推断为 cron");
        assertEquals("0/10 * * * * ?", def.cron());
        assertEquals("com.w3.taskscheduler.jobs.handler.TestHandler", def.handler());
        assertEquals(Duration.ofSeconds(60), def.timeout());
        assertEquals(3, def.maxRetries());
        assertEquals(Duration.ofSeconds(5), def.retryDelay());
        assertEquals(Boolean.FALSE, def.allowConcurrent());
        assertFalse(def.runOnStartup(), "run_on_startup 缺省应为 false");
        assertNull(def.interval());
        assertNull(def.intervalMode());

        Map<String, Object> params = def.params();
        assertEquals(2, params.size());
        assertEquals("api", params.get("source"));
        assertEquals(100, ((Number) params.get("batch-size")).intValue());
    }

    // ---------- interval 推断 + 宽松缺省 ----------

    @Test
    void intervalRowWithoutTriggerInferredWithDefaults() {
        SchedulerJobPO po = row("id-interval-1", "heartbeat");
        po.setCron(null);
        po.setInterval("200s");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        TaskDefinition def = loader.map(po);

        assertEquals("interval", def.trigger(), "trigger 为空、interval 有值时应推断为 interval");
        assertEquals(Duration.ofSeconds(200), def.interval());
        assertNull(def.intervalMode(), "interval_mode 缺省应为 null（注册时按 rate 处理）");
        assertFalse(def.runOnStartup());
        assertNull(def.allowConcurrent(), "allow_concurrent 为空应保持 null（跟随全局默认）");
        assertNull(def.params());
        assertNull(def.cron());
    }

    @Test
    void intervalRowExplicitTriggerIntervalModeAndRunOnStartup() {
        SchedulerJobPO po = row("id-interval-2", "hourly");
        po.setTrigger("interval");
        po.setInterval("PT1H"); // ISO-8601 写法
        po.setIntervalMode("delay");
        po.setRunOnStartup(true);
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        TaskDefinition def = loader.map(po);

        assertEquals("interval", def.trigger());
        assertEquals(Duration.ofHours(1), def.interval());
        assertEquals(IntervalMode.DELAY, def.intervalMode());
        assertTrue(def.runOnStartup());
    }

    // ---------- trigger 无法推断 / 非法取值：快速失败 ----------

    @Test
    void missingBothCronAndIntervalFailsFast() {
        SchedulerJobPO po = row("id-bad-1", "bad");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.map(po));
        assertTrue(e.getMessage().contains("无法推断"), e.getMessage());
    }

    @Test
    void bothCronAndIntervalWithoutTriggerFailsFast() {
        SchedulerJobPO po = row("id-bad-2", "bad");
        po.setCron("0/5 * * * * ?");
        po.setInterval("200s");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        assertThrows(IllegalArgumentException.class, () -> loader.map(po));
    }

    @Test
    void invalidIntervalModeFailsFast() {
        SchedulerJobPO po = row("id-bad-3", "bad");
        po.setTrigger("interval");
        po.setInterval("200s");
        po.setIntervalMode("every-day");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.map(po));
        assertTrue(e.getMessage().contains("interval_mode 非法"), e.getMessage());
    }

    // ---------- 时长 / params 解析失败：快速失败 ----------

    @Test
    void invalidDurationFailsFast() {
        SchedulerJobPO po = row("id-bad-4", "bad");
        po.setTrigger("interval");
        po.setInterval("10x");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.map(po));
        assertTrue(e.getMessage().contains("无法解析为时长"), e.getMessage());
    }

    @Test
    void invalidParamsJsonFailsFast() {
        SchedulerJobPO po = row("id-bad-5", "bad");
        po.setTrigger("cron");
        po.setCron("0/5 * * * * ?");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");
        po.setParams("not-a-json{");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.map(po));
        assertTrue(e.getMessage().contains("params 不是合法的 JSON 对象"), e.getMessage());
    }

    @Test
    void paramsJsonArrayFailsFast() {
        SchedulerJobPO po = row("id-bad-6", "bad");
        po.setTrigger("cron");
        po.setCron("0/5 * * * * ?");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");
        po.setParams("[1,2,3]");

        assertThrows(IllegalArgumentException.class, () -> loader.map(po));
    }

    // ---------- 复用 YAML 校验规则：快速失败 ----------

    @Test
    void invalidCronFailsFastLikeYaml() {
        SchedulerJobPO po = row("id-bad-7", "bad");
        po.setTrigger("cron");
        po.setCron("not-a-cron");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.map(po));
        assertTrue(e.getMessage().contains("cron 非法"), e.getMessage());
    }

    @Test
    void blankHandlerFailsFastLikeYaml() {
        SchedulerJobPO po = row("id-bad-8", "bad");
        po.setTrigger("cron");
        po.setCron("0/5 * * * * ?");
        po.setHandler("   ");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.map(po));
        assertTrue(e.getMessage().contains("handler 不能为空"), e.getMessage());
    }

    @Test
    void cronTaskWithIntervalConfiguredFailsFastLikeYaml() {
        SchedulerJobPO po = row("id-bad-9", "bad");
        po.setTrigger("cron");
        po.setCron("0/5 * * * * ?");
        po.setInterval("200s");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");

        assertThrows(IllegalArgumentException.class, () -> loader.map(po));
    }

    private static SchedulerJobPO row(String id, String name) {
        SchedulerJobPO po = new SchedulerJobPO();
        po.setId(id);
        po.setName(name);
        po.setEnable(true);
        return po;
    }
}
