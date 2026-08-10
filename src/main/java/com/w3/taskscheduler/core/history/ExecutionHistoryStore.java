package com.w3.taskscheduler.core.history;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.model.ExecutionRecord;

import lombok.extern.slf4j.Slf4j;

/**
 * 单次执行上下文记录与内存环形存储（可查询）
 */
@Slf4j
@Component
public class ExecutionHistoryStore {
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<ExecutionRecord>> store = new ConcurrentHashMap<>();
    private final int capacity = 1000; // 来自 SchedulerProperties

    public void add(ExecutionRecord r) {
        store.computeIfAbsent(r.executionId(), k -> new ConcurrentLinkedDeque<>()).addFirst(r);
        // 超容量移除最旧（略）
        // log.debug("记录:{}", r);
    }
}
