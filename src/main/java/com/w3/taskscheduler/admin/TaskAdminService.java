package com.w3.taskscheduler.admin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import com.w3.taskscheduler.admin.dto.TaskUpsertRequest;
import com.w3.taskscheduler.admin.dto.TaskView;
import com.w3.taskscheduler.core.config.DatabaseTaskConfigLoader;
import com.w3.taskscheduler.core.persistence.entity.SchedulerJobPO;
import com.w3.taskscheduler.core.persistence.repository.SchedulerJobRepository;
import com.w3.taskscheduler.core.scheduler.SchedulerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务管理服务：REST 修改任务数据的管理路径。
 * <p>
 * 所有增/删/改/启停<b>只写 t_scheduler_job</b>（不碰 task.yaml），写成功后调用
 * {@link SchedulerService#reload()} 做全量快照 diff 增量生效（无变化时 reload 零改动）；
 * 手动 trigger 不写库，仅触发一次执行。
 * <p>
 * 写前校验：把“目标行”转成 {@code TaskDefinition} 复用加载/校验规则（trigger 推断、时长、params JSON、
 * cron/handler 合法性），不合法返回 400 不让坏数据进库。
 * <p>
 * 注意：{@code scheduler.task-source: yaml}（强制 YAML）模式下写库照常成功，但 reload 按 YAML 源执行、
 * 改动不会生效，需切回 auto 后 reload/重启。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAdminService {

    /** params 序列化/解析用（本地自建，不依赖容器是否配置 ObjectMapper Bean） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchedulerJobRepository repository;
    /** 复用库行→TaskDefinition 的映射与校验（toTaskDefinition 不依赖仓库，可直接做写前校验） */
    private final DatabaseTaskConfigLoader loader;
    private final SchedulerService schedulerService;

    // ---------- 查询（读 DB，不触发 reload） ----------

    public List<TaskView> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(SchedulerJobPO::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SchedulerJobPO::getId))
                .map(this::toView)
                .toList();
    }

    public TaskView get(String id) {
        return toView(requireRow(id));
    }

    // ---------- 增删改（写 DB + 自动 reload） ----------

    @Transactional
    public TaskView create(TaskUpsertRequest request) {
        String id = request.id() == null || request.id().isBlank() ? null : request.id().trim();
        if (id == null) {
            id = UuidCreator.getTimeOrderedEpoch().toString();
        } else if (repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已存在: " + id);
        }
        SchedulerJobPO po = toPo(request, id);
        validateRow(po);
        repository.save(po);
        reloadAfterWrite(id);
        log.info("event=admin.task.create id={} name={}", id, po.getName());
        return toView(po);
    }

    @Transactional
    public TaskView update(String id, TaskUpsertRequest request) {
        requireRow(id); // 404 if absent
        SchedulerJobPO po = toPo(request, id);
        validateRow(po);
        repository.save(po);
        reloadAfterWrite(id);
        log.info("event=admin.task.update id={} name={}", id, po.getName());
        return toView(po);
    }

    @Transactional
    public void delete(String id) {
        requireRow(id);
        repository.deleteById(id);
        reloadAfterWrite(id);
        log.info("event=admin.task.delete id={}", id);
    }

    // ---------- 启停（持久化写 DB enable 列 + 自动 reload）/ 手动触发（不写库） ----------

    @Transactional
    public TaskView setEnabled(String id, boolean enable) {
        SchedulerJobPO po = requireRow(id);
        po.setEnable(enable);
        repository.save(po);
        reloadAfterWrite(id);
        log.info("event=admin.task.{} id={} name={}", enable ? "enable" : "disable", id, po.getName());
        return toView(po);
    }

    /** 手动触发一次（不写库、不 reload），任务需在调度器内存快照中 */
    public void trigger(String id) {
        try {
            schedulerService.triggerTask(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 手动 reload：DB 无变化时 no-op（diff 增量生效） */
    public void reload() {
        try {
            schedulerService.reload();
        } catch (RuntimeException e) {
            log.error("event=admin.reload.failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "reload 失败: " + e.getMessage(), e);
        }
    }

    // ---------- helpers ----------

    /** 写前校验：目标行需能通过加载/校验规则（含 trigger 推断），否则 400 */
    private void validateRow(SchedulerJobPO po) {
        try {
            loader.toTaskDefinition(po);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** DB 已写成功，触发 reload 生效；reload 失败说明存在外部坏数据等异常，返回 500 并说明 DB 已更新 */
    private void reloadAfterWrite(String id) {
        try {
            schedulerService.reload();
        } catch (RuntimeException e) {
            log.error("event=admin.reload.failed id={}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "DB 已更新(id=" + id + ")但 reload 未生效: " + e.getMessage(), e);
        }
    }

    private SchedulerJobPO requireRow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + id));
    }

    private SchedulerJobPO toPo(TaskUpsertRequest r, String id) {
        SchedulerJobPO po = new SchedulerJobPO();
        po.setId(id);
        po.setName(r.name());
        po.setEnable(r.enable() == null || r.enable());
        po.setTrigger(r.trigger());
        po.setCron(r.cron());
        po.setInterval(r.interval());
        po.setIntervalMode(r.interval_mode());
        po.setRunOnStartup(r.run_on_startup());
        po.setHandler(r.handler());
        po.setDescription(r.description());
        po.setTimeOut(r.time_out());
        po.setMaxRetries(r.max_retries() == null ? 0 : r.max_retries());
        po.setRetryDelay(r.retry_delay());
        po.setAllowConcurrent(r.allow_concurrent());
        po.setParams(writeParams(r.params()));
        return po;
    }

    private TaskView toView(SchedulerJobPO po) {
        return new TaskView(
                po.getId(), po.getName(), po.isEnable(), po.getTrigger(), po.getCron(), po.getInterval(),
                po.getIntervalMode(), po.getRunOnStartup(), po.getHandler(), po.getDescription(), po.getTimeOut(),
                po.getMaxRetries(), po.getRetryDelay(), po.getAllowConcurrent(), readParams(po.getParams()));
    }

    /** params JSON 对象 → 文本存储；非法序列化场景抛 400 语义异常 */
    private static String writeParams(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "params 序列化失败: " + e.getMessage());
        }
    }

    /** 存储文本 → JSON 对象（便于客户端直接使用）；解析失败时原样返回原始文本 */
    private static Object readParams(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            return text;
        }
    }
}
