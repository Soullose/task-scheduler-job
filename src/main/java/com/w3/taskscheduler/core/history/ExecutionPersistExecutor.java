package com.w3.taskscheduler.core.history;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行记录落库的专属虚拟线程执行器：把"写执行记录表"这段 JDBC I/O 从执行业务的虚拟线程上摘下来。
 *
 * <p>定位：落库等价于写日志，属于"尽力而为"的旁路操作——事件发布方 O(1) 返回（只做一次入队），
 * 落库失败（含停机窗口内被拒）只累计失败数 + 打 warn 日志，<b>绝不上抛影响业务线程</b>。</p>
 *
 * <p>为什么不复用 {@code virtualTaskExecutor}：该执行器被
 * {@link com.w3.taskscheduler.monitor.CountingExecutorService} 包装，其计数直接支撑两个对外指标——
 * {@code scheduler.vt.alive}（存活虚拟线程数，即监控里的存活数水位告警）与
 * {@code scheduler.task.submitted.total}（语义为"累计提交<b>触发</b>次数 = 累计创建虚拟线程数"）。
 * 落库任务若混入，每触发一次任务会产生两条虚拟线程，这两个指标的语义会直接失真，
 * 因此此处刻意使用独立执行器、独立线程名前缀 {@code vt-exec-persist-}。</p>
 *
 * <p>取舍：独立执行器无法蹭 {@link com.w3.taskscheduler.core.scheduler.SchedulerLifecycle} 的停机流程，
 * 由该类在业务执行器停机之后显式调用 {@link #drain(Duration)} 做 best-effort 收尾。
 * 正常重启/发布基本不丢记录；进程被强杀仍可能丢在途记录——这是"落库不影响业务"的必然代价。</p>
 */
@Slf4j
@Component
public class ExecutionPersistExecutor {

    /** 落库失败/丢弃的累计次数：供监控指标读取（gauge 口径，与 scheduler.vt.created.total 一致） */
    private final AtomicLong failed = new AtomicLong();

    /** 专属虚拟线程执行器：无界、每任务一线程；虚拟线程廉价，故不做队列与限流 */
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("vt-exec-persist-", 0).factory()
    );

    /**
     * 异步提交一次落库，保证快速返回：正常路径只做一次入队；执行器已停机时也只记一笔失败。
     *
     * @param writeTask 落库动作，实际在虚拟线程上执行
     */
    public void submit(Runnable writeTask) {
        try {
            executor.execute(() -> {
                try {
                    writeTask.run();
                } catch (Throwable t) {
                    // 兜底：落库是旁路操作，任何异常都在这里终止，不允许扩散
                    failed.incrementAndGet();
                    log.warn("执行记录落库失败，已丢弃该条记录（不影响业务）：{}", t.toString());
                    log.debug("执行记录落库失败详情", t);
                }
            });
        } catch (RejectedExecutionException e) {
            // 停机窗口内提交（业务线程还在收尾）：同样不能影响业务线程，只记账
            failed.incrementAndGet();
            log.warn("落库执行器已停止，丢弃该条执行记录（不影响业务）");
        }
    }

    /**
     * 停机收尾：等待在途落库写完，超时即放弃，避免把停机卡死。
     *
     * <p>必须在业务执行器停机<b>之后</b>调用——业务线程还在跑就会继续产生新记录，
     * 先关这里会把它们的落库请求全部推入拒绝路径。</p>
     *
     * @param timeout 最长等待时长
     */
    public void drain(Duration timeout) {
        executor.shutdown(); // 不再接收新任务
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("执行记录落库未在 {} 内写完，放弃剩余记录", timeout);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 累计落库失败/丢弃次数 */
    public long failed() {
        return failed.get();
    }

    /** 容器销毁兜底：即便停机流程没走到（如启动失败、测试上下文），也不留下未关闭的执行器 */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
