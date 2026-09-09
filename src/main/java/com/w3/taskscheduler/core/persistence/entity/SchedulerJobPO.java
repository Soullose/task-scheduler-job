package com.w3.taskscheduler.core.persistence.entity;

import com.github.f4b6a3.uuid.UuidCreator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * {@code t_scheduler_job} 任务表实体：调度进程启动时任务定义的主来源（{@code scheduler.task-source: auto}）。
 * <p>
 * 进程对该表<b>只读</b>，任务行由外部维护（运维 SQL / 将来的管理端）灌入；
 * 表为空或读库失败时由上层兜底读取 YAML。列与 {@link com.w3.taskscheduler.core.model.TaskDefinition}
 * 等价，便于“库行 → TaskDefinition”无损映射；可空列允许缺省，由读取侧按宽松规则
 * 推断 / 补默认（见 {@code DatabaseTaskConfigLoader}）：
 * <ul>
 * <li>{@code trigger} 为空时按 {@code cron}/{@code interval} 是否有值推断；</li>
 * <li>{@code interval_mode} 缺省 {@code rate}、{@code run_on_startup} 缺省 {@code false}；</li>
 * <li>{@code time_out}/{@code retry_delay}/{@code interval} 存时长文本（如
 * {@code 60s}/{@code 5s}/{@code 200s}）；</li>
 * <li>{@code allow_concurrent} 为空 = 跟随全局默认；{@code params} 存 JSON 对象文本。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "t_scheduler_job", comment = "任务表")
public class SchedulerJobPO {

    @Id
    @Column(name = "id", length = 50, nullable = false, comment = "主键（即调度 taskId）")
    private String id = UuidCreator.getTimeOrderedEpoch().toString();

    @Column(name = "name", length = 200, nullable = false, comment = "任务名")
    private String name;

    @Column(name = "enable", nullable = false, comment = "是否启用")
    private boolean enable = true;

    @Column(name = "trigger", length = 16, comment = "调度模式: cron|interval；为空时按 cron/interval 列推断")
    private String trigger;

    @Column(name = "cron", length = 50, comment = "cron 表达式（Spring 六字段秒级）")
    private String cron;

    @Column(name = "interval", length = 50, comment = "固定间隔时长文本（trigger=interval，如 200s/3m/2h/PT1H）")
    private String interval;

    @Column(name = "interval_mode", length = 16, comment = "interval 推进模式: rate(缺省)/delay")
    private String intervalMode;

    @Column(name = "run_on_startup", comment = "启动后立即执行一次（enabled=true 时生效）；空=false")
    private Boolean runOnStartup;

    @Column(name = "handler", length = 500, nullable = false, comment = "执行类名")
    private String handler;

    @Column(name = "description", length = 500, comment = "任务描述（仅管理展示，调度不消费）")
    private String description;

    @Column(name = "time_out", length = 50, comment = "单次执行超时文本（如 60s；空=不超时）")
    private String timeOut;

    @Column(name = "max_retries", nullable = false, comment = "失败后的最大重试次数(默认 0,即不重试)")
    private int maxRetries = 0;

    @Column(name = "retry_delay", length = 50, comment = "重试间隔文本（如 5s；空=null，当前实现未使用该间隔）")
    private String retryDelay;

    @Column(name = "allow_concurrent", comment = "任务级覆盖全局并发设置；空=跟随全局默认")
    private Boolean allowConcurrent;

    @Column(name = "params", columnDefinition = "TEXT", comment = "自定义参数（JSON 对象文本，如 {\"source\":\"api\"}）")
    private String params;
}
