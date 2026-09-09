package com.w3.taskscheduler.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.config.SchedulerProperties.TaskSource;
import com.w3.taskscheduler.core.model.TaskDefinition;

/**
 * {@link TaskConfigSource} 启动任务源编排测试：
 * yaml 强制模式跳过 DB；auto 模式 DB 有行→以 DB 为准；表为空/读库失败→兜底 YAML；
 * 行数据不合法（快速失败）→ 不兜底、异常向上抛。
 */
class TaskConfigSourceTests {

    private SchedulerProperties props;
    private StubYamlLoader yaml;
    private StubDbLoader db;

    @BeforeEach
    void setUp() {
        props = new SchedulerProperties();
        props.setTaskConfigLocation("classpath:scheduler/tasks.yaml");
    }

    // ---------- yaml 强制模式：跳过 DB ----------

    @Test
    void forcedYamlSkipsDb() throws IOException {
        props.setTaskSource(TaskSource.YAML);
        List<TaskDefinition> yamlDefs = List.of(def("yaml-task"));
        List<TaskDefinition> dbDefs = List.of(def("db-task"));
        init(yamlDefs, dbDefs, null);

        List<TaskDefinition> result = source().loadStartupTasks();

        assertSame(yamlDefs, result, "yaml 强制模式应返回 YAML 定义");
        assertEquals(1, yaml.calls, "yaml 加载应执行一次");
        assertEquals(0, db.calls, "yaml 强制模式不应读 DB");
    }

    // ---------- auto 模式：DB 有行 → 全部以 DB 为准 ----------

    @Test
    void autoWithRowsPrefersDbAndIgnoresYaml() throws IOException {
        props.setTaskSource(TaskSource.AUTO);
        List<TaskDefinition> yamlDefs = List.of(def("yaml-task"));
        List<TaskDefinition> dbDefs = List.of(def("db-task-1"), def("db-task-2"));
        init(yamlDefs, dbDefs, null);

        List<TaskDefinition> result = source().loadStartupTasks();

        assertSame(dbDefs, result, "auto 模式 DB 有行时应全部以 DB 为准");
        assertEquals(1, db.calls);
        assertEquals(0, yaml.calls, "DB 有行时不应读 YAML");
    }

    // ---------- auto 模式：表为空 → 兜底 YAML ----------

    @Test
    void autoWithEmptyTableFallsBackToYaml() throws IOException {
        props.setTaskSource(TaskSource.AUTO);
        List<TaskDefinition> yamlDefs = List.of(def("yaml-task"));
        init(yamlDefs, List.of(), null);

        List<TaskDefinition> result = source().loadStartupTasks();

        assertSame(yamlDefs, result, "DB 表为空时应整份兜底 YAML");
        assertEquals(1, db.calls);
        assertEquals(1, yaml.calls);
    }

    // ---------- auto 模式：读库失败 → 兜底 YAML ----------

    @Test
    void autoWithDbReadFailureFallsBackToYaml() throws IOException {
        props.setTaskSource(TaskSource.AUTO);
        List<TaskDefinition> yamlDefs = List.of(def("yaml-task"));
        init(yamlDefs, null, new DataAccessException("connection refused") { });

        List<TaskDefinition> result = source().loadStartupTasks();

        assertSame(yamlDefs, result, "读库层失败（DataAccessException）时应兜底 YAML");
        assertEquals(1, db.calls);
        assertEquals(1, yaml.calls);
    }

    // ---------- auto 模式：行数据不合法 → 快速失败，不兜底 ----------

    @Test
    void autoWithInvalidRowFailsFastWithoutYamlFallback() {
        props.setTaskSource(TaskSource.AUTO);
        init(List.of(def("yaml-task")), null,
                new IllegalArgumentException("任务行数据不合法 id=bad name=x：trigger 非法"));

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> source().loadStartupTasks());

        assertTrue(e.getMessage().contains("任务行数据不合法"), e.getMessage());
        assertEquals(1, db.calls);
        assertEquals(0, yaml.calls, "行数据不合法应快速失败，不应兜底 YAML");
    }

    // ---------- helpers ----------

    private TaskConfigSource source() {
        return new TaskConfigSource(props, yaml, db);
    }

    private void init(List<TaskDefinition> yamlDefs, List<TaskDefinition> dbDefs, RuntimeException dbFailure) {
        yaml = new StubYamlLoader(yamlDefs);
        db = new StubDbLoader(dbDefs, dbFailure);
    }

    private static TaskDefinition def(String taskId) {
        return new TaskDefinition(
                taskId, taskId + "-name", true, "cron", "0/5 * * * * ?",
                "com.w3.taskscheduler.jobs.handler.TestHandler",
                Duration.ofSeconds(60), 0, null, null, false, null, null, null);
    }

    /** 记录调用次数的 YAML 加载桩：绕过真实文件读取 */
    private static class StubYamlLoader extends TaskConfigLoader {
        private final List<TaskDefinition> result;
        private int calls;

        StubYamlLoader(List<TaskDefinition> result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public List<TaskDefinition> load() throws IOException {
            calls++;
            return result;
        }
    }

    /** 记录调用次数的 DB 加载桩：模拟有行 / 空表 / 读库失败 / 数据不合法 */
    private static class StubDbLoader extends DatabaseTaskConfigLoader {
        private final List<TaskDefinition> result;
        private final RuntimeException failure;
        private int calls;

        StubDbLoader(List<TaskDefinition> result, RuntimeException failure) {
            super(null, null);
            this.result = result;
            this.failure = failure;
        }

        @Override
        public List<TaskDefinition> load() {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
