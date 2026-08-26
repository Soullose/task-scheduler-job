package com.w3.taskscheduler.monitor;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import lombok.RequiredArgsConstructor;

/**
 * 自计数执行器：精确统计存活虚拟线程数。
 * JDK 21 公共采样 API（getAllStackTraces/enumerate/ThreadMXBean）不含虚拟线程，
 * 因此存活数必须在应用层维护：提交 +1、任务结束 finally -1。
 */
@RequiredArgsConstructor
public class CountingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final AtomicLong alive = new AtomicLong();
    private final AtomicLong created = new AtomicLong();

    @Override
    public void execute(Runnable command) {
        created.incrementAndGet();
        alive.incrementAndGet();
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    alive.decrementAndGet(); // 虚拟线程结束 → 存活数-1（异常路径不漏减）
                }
            });
        } catch (RejectedExecutionException e) {
            alive.decrementAndGet(); // 提交被拒 → 任务没跑，回滚计数；异常上抛供 R7 告警
            throw e;
        }
    }

    public long alive() {
        return alive.get();
    }

    public long created() {
        return created.get();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

}
