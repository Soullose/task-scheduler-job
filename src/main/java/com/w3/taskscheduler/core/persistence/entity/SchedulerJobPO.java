package com.w3.taskscheduler.core.persistence.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "t_scheduler_job", comment = "任务表")
public class SchedulerJobPO {

    @Id
    @Column(name = "id", length = 50, comment = "主键")
    private String id;

    @Column(name = "name", length = 200, comment = "任务名")
    private String name;

    @Column(name = "enable", comment = "是否启用")
    private boolean enable = true;

    @Column(name = "cron", length = 50, comment = "cron表达式")
    private String cron;

    @Column(name = "handler", length = 500, comment = "执行类名")
    private String handler;

    @Column(name = "description", length = 500, comment = "任务描述")
    private String description;

    @Column(name = "time_out", length = 50, comment = "超时时间")
    private String timeOut;

    @Column(name = "max_retries", comment = "失败后的最大重试次数(默认 0,即不重试)")
    private int maxRetries;

    @Column(name = "retry_delay", comment = "重试间隔")
    private String retryDelay;

    @Column(name = "allow_concurrent", comment = "任务级覆盖全局并发设置")
    private boolean allowConcurrent;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "params", comment = "自定义参数")
    private String params;
}
