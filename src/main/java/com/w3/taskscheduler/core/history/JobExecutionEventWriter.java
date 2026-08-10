package com.w3.taskscheduler.core.history;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JobExecutionEventWriter {

    @EventListener
    public void onExecutionRecord(ExecutionRecordEvent event) {
        log.debug("event:{}", event);
    }
}
