package com.w3.taskscheduler.jobs.handler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.persistence.repository.ExecutionRecordRepository;
import com.w3.taskscheduler.core.scheduler.ScheduledTaskHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 历史执行记录清理任务：清理 {@code t_job_execution}（{@link com.w3.taskscheduler.core.persistence.entity.JobExecutionPO}）。
 *
 * <p><b>清理规则</b>（与调度定义解耦，规则本身硬编码在本类中）：
 * <ul>
 * <li>只清理 {@code execution_status = 'SUCCESS'} 的记录——RUNNING/FAILED/TIMEOUT/SKIPPED/INTERRUPTED
 * 一律不动，失败记录需要保留用于排查；</li>
 * <li>保留最近 {@value #RETENTION_HOURS} 小时：删除 {@code end_at < 当前时间 - 12h} 的记录，
 * 时间基准取 {@code end_at}（SUCCESS 记录必然已写入 end_at）；</li>
 * <li>物理删除（DELETE），不做逻辑删除/归档。</li>
 * </ul>
 *
 * <p><b>分批策略</b>：每批最多 {@value #BATCH_SIZE} 条，循环删除直到某批不足一批（含 0 条）为止。
 * 单批一个独立事务（见 {@link ExecutionRecordRepository#deleteSuccessBatchBefore}），
 * 因此无论积压多少数据，都不会产生超长事务或长时间锁表；即使中途失败，
 * 已提交的批次也不会回滚，未删完的部分下次执行会继续删（保留期语义下判据只与 end_at 有关，不会漏删）。
 *
 * <p>调度定义见 {@code scheduler/tasks.yaml}：每天 00:00（{@code cron 0 0 0 * * ?}）执行一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutionCleanupHandler implements ScheduledTaskHandler {

    /** 保留期：只删除 end_at 早于「当前时间 - 12 小时」的记录 */
    private static final int RETENTION_HOURS = 12;

    /** 单批删除条数：分批循环删除，控制单事务大小与锁表时长 */
    private static final int BATCH_SIZE = 1000;

    private final ExecutionRecordRepository repository;

    @Override
    public void execute(TaskContext ctx) throws Exception {
        Instant cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
        long total = 0;
        int batch;
        do {
            batch = repository.deleteSuccessBatchBefore(cutoff, BATCH_SIZE);
            total += batch;
            log.debug(
                    "[{}] 分批清理中：本批删除 {} 条，累计 {} 条（cutoff={}）",
                    ctx.task().name(), batch, total, cutoff
            );
        } while (batch == BATCH_SIZE);

        log.info(
                "[{}] 清理完成：删除 SUCCESS 且 end_at < {} 的执行记录共 {} 条（保留最近 {} 小时）",
                ctx.task().name(), cutoff, total, RETENTION_HOURS
        );
    }
}
