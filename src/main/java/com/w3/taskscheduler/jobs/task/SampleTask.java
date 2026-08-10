package com.w3.taskscheduler.jobs.task;

import org.springframework.stereotype.Service;

import com.w3.taskscheduler.core.model.TaskContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SampleTask {
    /** 支持 (TaskContext) / () / (Map) 三种签名；推荐 (TaskContext) 以获取 executionId/params */
    public void execute(TaskContext ctx) throws Exception {
        log.debug(
                "[{}] 执行开始 executionId={} params={}",
                ctx.task().name(), ctx.executionId(), ctx.params()
        );
        Thread.sleep(500); // 示例阻塞——发生在虚拟线程内，不占平台线程
        log.debug("[{}] 执行结束", ctx.task().name());
    }
}
