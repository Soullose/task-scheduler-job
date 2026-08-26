package com.w3.taskscheduler.monitor;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.model.ExecutionStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 注册调度器自定义指标。Micrometer 由 spring-boot-starter-actuator 传递引入，
 * 无外部 registry 时 Boot 自动装配 SimpleMeterRegistry，指标即可在 /actuator/metrics 查询。
 */
@Component
@RequiredArgsConstructor
public class SchedulerMetrics {
    private final CountingExecutorService countingExecutor;
    private final MeterRegistry meterRegistry;

    private Counter success;
    private Counter failed;
    private Counter timeout;
    private Counter skipped;
    private Counter retry;

    @PostConstruct
    void register() {
        Gauge.builder("scheduler.vt.alive", countingExecutor, CountingExecutorService::alive)
                .description("当前存活的调度虚拟线程数（自计数）")
                .register(meterRegistry);
        Gauge.builder("scheduler.vt.created.total", countingExecutor, CountingExecutorService::created)
                .description("累计创建的虚拟线程数")
                .register(meterRegistry);

        Gauge.builder("scheduler.task.submitted.total", countingExecutor, CountingExecutorService::created)
                .description("累计提交触发次数（= 累计创建虚拟线程数）")
                .register(meterRegistry);

        success = Counter.builder("scheduler.task.success.total").description("累计成功次数").register(meterRegistry);

        failed = Counter.builder("scheduler.task.failed.total").description("累计失败次数").register(meterRegistry);

        timeout = Counter.builder("scheduler.task.timeout.total").description("累计超时次数").register(meterRegistry);

        skipped = Counter.builder("scheduler.task.skipped.total").description("累计跳过次数").register(meterRegistry);

        retry = Counter.builder("scheduler.task.retry.total").description("累计重试次数").register(meterRegistry);

        Timer.builder("scheduler.task.duration.seconds")
                .description("单次触发业务执行耗时（含重试），按终态打 tag")
                .publishPercentileHistogram() // 可选：浏览器 JSON 里能看到耗时分布
                .register(meterRegistry);
    }

    public void markSuccess() {
        success.increment();
    }

    public void markFailed() {
        failed.increment();
    }

    public void markTimeout() {
        timeout.increment();
    }

    public void markSkipped() {
        skipped.increment();
    }

    public void markRetries(int n) {
        retry.increment(n);
    }

    public void recordDuration(ExecutionStatus status, long nanos) {
        meterRegistry.timer("scheduler.duration", "status", status.name())
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
