package com.w3.taskscheduler.core.scheduler;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.history.ExecutionPersistExecutor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 容器启动自动 start()；关闭时按序优雅停机
 *
 * <p>停机顺序：取消调度 → 停业务虚拟线程执行器并等在途业务跑完 → 最后 flush 异步落库队列。
 * 顺序不可颠倒：业务线程还在跑就会继续产生执行记录，先关落库执行器会把它们的落库请求全部推入拒绝路径。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerLifecycle implements SmartLifecycle {
    private final SchedulerService schedulerService;
    private final ExecutorService virtualExecutor;
    private final ExecutionPersistExecutor persistExecutor;
    private final int shutdownTimeoutSeconds = 30;

    /** 停机时等待在途执行记录落库的最长时间（best-effort，超时放弃，不阻塞停机） */
    private static final Duration PERSIST_DRAIN_TIMEOUT = Duration.ofSeconds(5);
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

        // 业务线程已全部收尾，再把在途的执行记录落库 flush 掉（best-effort，最多等 PERSIST_DRAIN_TIMEOUT）
        persistExecutor.drain(PERSIST_DRAIN_TIMEOUT);
        log.info("调度器已优雅停机");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
