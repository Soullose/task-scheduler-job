package com.w3.taskscheduler.core.config;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.w3.taskscheduler.core.model.IntervalMode;
import com.w3.taskscheduler.core.model.TaskDefinition;
import com.w3.taskscheduler.core.persistence.entity.SchedulerJobPO;
import com.w3.taskscheduler.core.persistence.repository.SchedulerJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 从 {@code t_scheduler_job} 任务表读取启动任务（DB 任务源，进程只读）。
 * <p>
 * 规则（宽松缺省 + 快速失败，与 YAML 校验规则一致，只是缺省更宽容）：
 * <ul>
 * <li>{@code trigger} 为空时按 {@code cron}/{@code interval} 推断：cron 有值→cron，否则 interval 有值→interval，
 *     两者同有或同无→数据不合法；</li>
 * <li>{@code interval_mode} 缺省 {@code rate}（注册时按 rate 处理）、{@code run_on_startup} 缺省 {@code false}；</li>
 * <li>{@code time_out}/{@code retry_delay}/{@code interval} 支持与 YAML 一致的时长文本（如 60s/200s/2h/PT1H），空=不超时/不重试；</li>
 * <li>{@code allow_concurrent} 为空 → 跟随全局默认；{@code params} 为空 → 无参数，否则须为合法 JSON 对象；</li>
 * <li>行数据不合法（含校验失败）抛 {@link IllegalArgumentException} 快速失败，交由上层决定是否兜底
 *     （{@link TaskConfigSource} 只对空表与 {@link DataAccessException} 读库失败兜底，不吞数据错误）。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DatabaseTaskConfigLoader {

    /** 简单时长格式：数字 + 单位(ns/us/ms/s/m/h/d)，如 200s/3m/2h；ISO-8601(PT1H) 走 Duration.parse */
    private static final Pattern DURATION_PATTERN = Pattern.compile("([+-]?\\d+)(ns|us|ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    /** params JSON 解析用（本地自建，不依赖容器是否配置 ObjectMapper Bean） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SchedulerJobRepository schedulerJobRepository;
    private final TaskConfigLoader taskConfigLoader;

    /**
     * 读取任务表全部行并映射为任务定义；表为空返回空列表（是否兜底 YAML 由上层决定）。
     *
     * @throws DataAccessException     读库层失败（连接/查询异常），由上层据此兜底 YAML
     * @throws IllegalArgumentException 行数据不合法（快速失败）
     */
    public List<TaskDefinition> load() {
        List<SchedulerJobPO> rows = schedulerJobRepository.findAll().stream()
                .sorted(Comparator.comparing(SchedulerJobPO::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SchedulerJobPO::getId))
                .toList();
        if (rows.isEmpty()) {
            log.info("event=db.task.load rows=0 -> 任务表为空，交由上层决定是否兜底 YAML");
            return List.of();
        }
        List<TaskDefinition> defs = rows.stream().map(this::toTaskDefinition).toList();
        log.info("event=db.task.load rows={}", defs.size());
        return defs;
    }

    /**
     * 单行 → {@link TaskDefinition}（宽松缺省推断 + 复用 {@link TaskConfigLoader#validate} 快速失败）。
     * 供加载路径与 REST 增/改前的写前校验共用（不依赖仓库，可直接调用）。
     */
    public TaskDefinition toTaskDefinition(SchedulerJobPO row) {
        String label = row.getId() + "/" + row.getName();
        String trigger = trimToNull(row.getTrigger());
        String cron = trimToNull(row.getCron());
        String intervalText = trimToNull(row.getInterval());
        if (trigger == null) {
            if (cron != null && intervalText == null) {
                trigger = "cron";
            } else if (cron == null && intervalText != null) {
                trigger = "interval";
            } else {
                throw new IllegalArgumentException(badRow(row,
                        "trigger 为空且无法推断：cron 与 interval 必须恰好一个有值"));
            }
        }
        TaskDefinition def = new TaskDefinition(
                row.getId(),
                row.getName(),
                row.isEnable(),
                trigger,
                cron,
                row.getHandler(),
                parseDuration(row.getTimeOut(), "time_out", label),
                row.getMaxRetries(),
                parseDuration(row.getRetryDelay(), "retry_delay", label),
                row.getAllowConcurrent(),
                Boolean.TRUE.equals(row.getRunOnStartup()),
                parseDuration(intervalText, "interval", label),
                parseIntervalMode(trimToNull(row.getIntervalMode()), label),
                parseParams(row.getParams(), label)
        );
        try {
            taskConfigLoader.validate(def);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(badRow(row, e.getMessage()), e);
        }
        return def;
    }

    /** 时长文本 → Duration；空/空白 → null；非法则快速失败（提示支持的写法） */
    private Duration parseDuration(String text, String field, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String v = text.trim();
        try {
            return Duration.parse(v); // ISO-8601：PT1H / P1D / -PT30S ...
        } catch (DateTimeParseException ignored) {
            // fall through 到简单格式
        }
        Matcher m = DURATION_PATTERN.matcher(v);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "ns" -> Duration.ofNanos(n);
                case "us" -> Duration.ofNanos(Math.multiplyExact(n, 1000L));
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> throw new IllegalArgumentException(badDuration(v, field, label));
            };
        }
        throw new IllegalArgumentException(badDuration(v, field, label));
    }

    private static String badDuration(String v, String field, String label) {
        return label + " 的 " + field + " 无法解析为时长: '" + v + "'（支持 60s/200s/3m/2h/PT1H 等写法）";
    }

    private IntervalMode parseIntervalMode(String text, String label) {
        if (text == null) {
            return null; // 缺省：注册时按 rate 处理
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "rate" -> IntervalMode.RATE;
            case "delay" -> IntervalMode.DELAY;
            default -> throw new IllegalArgumentException(
                    label + " 的 interval_mode 非法: '" + text + "'（仅支持 rate/delay）");
        };
    }

    /** params JSON 对象文本 → Map；空 → null；非 JSON 对象快速失败 */
    private Map<String, Object> parseParams(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String v = text.trim();
        try {
            return objectMapper.readValue(v, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    label + " 的 params 不是合法的 JSON 对象: '" + v + "'", e);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String badRow(SchedulerJobPO row, String detail) {
        return "任务行数据不合法 id=" + row.getId() + " name=" + row.getName() + "：" + detail;
    }
}
