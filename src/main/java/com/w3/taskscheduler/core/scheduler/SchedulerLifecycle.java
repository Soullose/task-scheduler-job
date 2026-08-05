package com.w3.taskscheduler.core.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 容器启动自动 start()；关闭时按序优雅停机
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerLifecycle implements SmartLifecycle {
    private final SchedulerService schedulerService;
    private final ExecutorService virtualExecutor;
    private final int shutdownTimeoutSeconds = 30;
    private volatile boolean running;

    @Override
    public void start() {
        log.info("Starting Scheduler");
        schedulerService.start();
        running = true;
    }

    @Override
    public void stop() {
        log.info("Stopping Scheduler");
        if (!running)
            return;
        running = false;
        schedulerService.stop(); // 取消全部 ScheduledFuture

        virtualExecutor.shutdown(); // 停止接收新任务
        try {
            if (!virtualExecutor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("等待虚拟线程执行超时 {}s，强制中断", shutdownTimeoutSeconds);
                virtualExecutor.shutdownNow(); // ③ 中断剩余虚拟线程
                virtualExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            virtualExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("调度器已优雅停机");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
