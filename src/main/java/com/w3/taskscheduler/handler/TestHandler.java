package com.w3.taskscheduler.handler;

import org.springframework.stereotype.Service;

import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.scheduler.ScheduledTaskHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TestHandler implements ScheduledTaskHandler {

    @Override
    public void execute(TaskContext ctx) throws Exception {
        // log.info("ctx:{}", ctx);
        log.info("TestHandler");
    }

}
