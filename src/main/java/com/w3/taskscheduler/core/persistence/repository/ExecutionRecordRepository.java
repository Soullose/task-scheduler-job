package com.w3.taskscheduler.core.persistence.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.w3.taskscheduler.core.persistence.entity.JobExecutionPO;

public interface ExecutionRecordRepository extends JpaRepository<JobExecutionPO, String> {

    /**
     * 分批物理删除历史执行记录：只删 {@code execution_status = 'SUCCESS'} 且
     * {@code end_at < cutoff} 的行，单次最多 {@code batchSize} 条，按 {@code end_at} 从旧到新取。
     * <p>
     * <b>方法级 {@code @Transactional} 是刻意的</b>：调用方（清理任务）会循环调用本方法，
     * 事务挂在每次调用上而不是整个循环上，从而实现「一批一提交」——单事务不会随数据量膨胀，
     * 中途失败/中断时已删的批次保持已提交状态，不会整体回滚。
     * <p>
     * 用 {@code id IN (SELECT ... LIMIT n)} 而不是 {@code DELETE ... LIMIT}：
     * PostgreSQL 的 DELETE 本身不支持 LIMIT，子查询取主键是等价且可走索引的写法。
     *
     * @param cutoff    保留期截止时刻，删除 end_at 早于它的记录
     * @param batchSize 单批最大删除条数
     * @return 本批实际删除条数
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM t_job_execution
            WHERE id IN (
                SELECT id FROM t_job_execution
                WHERE execution_status = 'SUCCESS'
                  AND end_at < :cutoff
                ORDER BY end_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteSuccessBatchBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
