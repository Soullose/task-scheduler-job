package com.w3.taskscheduler.jobs.handler;

import org.springframework.stereotype.Service;

import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.scheduler.ScheduledTaskHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DataSyncHandler implements ScheduledTaskHandler {
    @Override
    public void execute(TaskContext ctx) throws Exception {
        log.info(
                "[{}] 数据同步开始, batchSize={}",
                ctx.task().name(), ctx.params().get("batch-size")
        );
        // 业务逻辑：拉取 API → 幂等入库（以 executionId 为幂等键）...
        Thread.sleep(2000); // 示例阻塞，发生在虚拟线程内
        log.info("[{}] 数据同步完成", ctx.task().name());
    }
}
