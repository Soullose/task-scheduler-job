package com.w3.taskscheduler.core.persistence.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.w3.taskscheduler.core.model.ExecutionRecord;
import com.w3.taskscheduler.core.model.ExecutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "t_job_execution", comment = "任务执行纪录表")
public class JobExecutionPO {

    @Id
    @Column(name = "id", comment = "主键id")
    private String id;

    @Column(name = "execution_id")
    private String executionId;

    @Column(name = "task_name")
    private String taskName;

    @Column(name = "cron")
    private String cron;

    @Column(name = "params")
    @JdbcTypeCode(SqlTypes.JSON) // params 用 Postgres jsonb 存
    private Map<String, Object> params;

    @Column(name = "triggered_at")
    private Instant triggeredAt;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "execution_status")
    @Enumerated(EnumType.STRING)
    private ExecutionStatus status;

    @Column(name = "attempts")
    private int attempts;

    @Column(name = "error_msg", length = 2000)
    private String errorMessage;

    // @Column(name = "exit_code")
    private Integer exitCode;

    public static JobExecutionPO from(ExecutionRecord r) {
        JobExecutionPO jobExecutionPO = new JobExecutionPO();
        jobExecutionPO.setId(UUID.randomUUID().toString());
        jobExecutionPO.setExecutionId(r.executionId());
        jobExecutionPO.setTaskName(r.taskName());
        jobExecutionPO.setCron(r.cron());
        jobExecutionPO.setParams(r.params());
        jobExecutionPO.setTriggeredAt(r.triggeredAt());
        jobExecutionPO.setStartAt(r.startAt());
        jobExecutionPO.setEndAt(r.endAt());
        jobExecutionPO.setStatus(r.status());
        jobExecutionPO.setAttempts(r.attempts());
        jobExecutionPO.setErrorMessage(r.errorMessage());
        jobExecutionPO.setExitCode(r.exitCode());
        return jobExecutionPO;
    }
}
