package com.w3.taskscheduler.core.history;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.w3.taskscheduler.core.persistence.entity.JobExecutionPO;
import com.w3.taskscheduler.core.persistence.repository.ExecutionRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobExecutionEventWriter {

    private final ExecutionRecordRepository repository;

    @TransactionalEventListener(fallbackExecution = true)
    public void onExecutionRecord(ExecutionRecordEvent event) {
        log.debug("event:{}", event);
        repository.save(JobExecutionPO.from(event.record()));
    }
}
