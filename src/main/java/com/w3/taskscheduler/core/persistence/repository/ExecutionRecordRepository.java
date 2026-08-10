package com.w3.taskscheduler.core.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.w3.taskscheduler.core.persistence.entity.JobExecutionPO;

public interface ExecutionRecordRepository extends JpaRepository<JobExecutionPO, UUID> {
}
