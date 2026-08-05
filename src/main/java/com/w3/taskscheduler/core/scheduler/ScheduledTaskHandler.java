package com.w3.taskscheduler.core.scheduler;

import com.w3.taskscheduler.core.model.TaskContext;

public interface ScheduledTaskHandler {
    void execute(TaskContext ctx) throws Exception;
}
