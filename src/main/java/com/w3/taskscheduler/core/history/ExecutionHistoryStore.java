package com.w3.taskscheduler.core.history;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.model.ExecutionRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 单次执行上下文记录与内存环形存储（可查询）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionHistoryStore {

    private final ApplicationEventPublisher publisher;

    public void add(ExecutionRecord r) {
        publisher.publishEvent(new ExecutionRecordEvent(r));
    }
}
