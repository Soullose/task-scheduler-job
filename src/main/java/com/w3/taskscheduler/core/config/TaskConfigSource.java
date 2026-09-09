package com.w3.taskscheduler.core.config;

import java.io.IOException;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.config.SchedulerProperties;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动任务源编排：按 {@code scheduler.task-source} 决定从哪里加载启动任务，并负责 YAML 兜底。
 * <ul>
 * <li>{@code YAML}：强制只读 YAML（{@code task-config-location}），跳过 DB；</li>
 * <li>{@code AUTO}（默认）：先读 {@code t_scheduler_job}（{@link DatabaseTaskConfigLoader}）——
 *     <ul>
 *     <li>读库成功且有行 → 全部以 DB 为准，忽略 YAML；</li>
 *     <li>表为空 / 读库层失败（{@link DataAccessException}，如连接或查询异常）→ 整份兜底读取 YAML；</li>
 *     <li>读到数据但某行不合法（{@link IllegalArgumentException}）→ 快速失败抛给启动流程，不兜底、不静默跳过。</li>
 *     </ul>
 * </li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class TaskConfigSource {

    private final SchedulerProperties schedulerProperties;
    /** YAML 任务加载器：DB 为空 / 读库失败 / 强制 yaml 时兜底使用 */
    private final TaskConfigLoader yamlLoader;
    /** DB 任务加载器：从 t_scheduler_job 读取（auto 模式主路径） */
    private final DatabaseTaskConfigLoader databaseLoader;

    /**
     * 按任务源配置加载启动任务定义列表（可能为空）。
     *
     * @throws IOException              兜底/强制读取 YAML 时文件缺失或解析失败
     * @throws IllegalArgumentException DB 行数据不合法（快速失败）
     */
    public List<TaskDefinition> loadStartupTasks() throws IOException {
        if (schedulerProperties.getTaskSource() == SchedulerProperties.TaskSource.YAML) {
            log.info("event=task.source source=yaml(forced) location={}", schedulerProperties.getTaskConfigLocation());
            return yamlLoader.load();
        }
        try {
            List<TaskDefinition> dbDefs = databaseLoader.load();
            if (dbDefs.isEmpty()) {
                log.warn("event=task.source db.rows=0 -> 任务表为空，兜底读取 YAML: {}",
                        schedulerProperties.getTaskConfigLocation());
                return yamlLoader.load();
            }
            log.info("event=task.source source=db tasks={}", dbDefs.size());
            return dbDefs;
        } catch (DataAccessException e) {
            log.warn("event=task.source db.read.failed -> 读库失败，兜底读取 YAML: {}（原因: {}）",
                    schedulerProperties.getTaskConfigLocation(), e.toString());
            return yamlLoader.load();
        }
    }
}
