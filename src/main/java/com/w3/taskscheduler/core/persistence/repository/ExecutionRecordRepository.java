package com.w3.taskscheduler.core.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.w3.taskscheduler.core.persistence.entity.JobExecutionPO;

public interface ExecutionRecordRepository extends JpaRepository<JobExecutionPO, String> {
}
