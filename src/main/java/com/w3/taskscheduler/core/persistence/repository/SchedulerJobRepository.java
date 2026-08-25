package com.w3.taskscheduler.core.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.w3.taskscheduler.core.persistence.entity.SchedulerJobPO;

public interface SchedulerJobRepository extends JpaRepository<SchedulerJobPO, String> {

}
