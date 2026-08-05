package com.w3.taskscheduler.core.model;

import java.time.Instant;
import java.util.Map;

public record TaskContext(
        String executionId,
        TaskDefinition task,
        Instant triggeredAt) {

    public Map<String, Object> params() {
        return task.params();
    }
}
