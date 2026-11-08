package com.w3.taskscheduler.core.history;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.w3.taskscheduler.core.persistence.entity.JobExecutionPO;
import com.w3.taskscheduler.core.persistence.repository.ExecutionRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行记录落库监听器。
 *
 * <p>本方法仍由事件发布线程同步调用（{@code @TransactionalEventListener} 的
 * {@code fallbackExecution = true} 表示"无事务时立即执行"），但方法体只做一件事：
 * 把 JDBC 写库丢给 {@link ExecutionPersistExecutor} 的虚拟线程。
 * 因此发布方（执行业务的虚拟线程）不再承担任何数据库 I/O 与等待。</p>
 *
 * <p>保留 {@code @TransactionalEventListener} 语义：将来若在事务中发布事件，仍是事务提交后才落库，
 * 只是落库动作本身异步。异常全部在 {@link ExecutionPersistExecutor#submit} 内终止并计数，
 * 不会回传到业务线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobExecutionEventWriter {

    private final ExecutionRecordRepository repository;

    private final ExecutionPersistExecutor persistExecutor;

    @TransactionalEventListener(fallbackExecution = true)
    public void onExecutionRecord(ExecutionRecordEvent event) {
        // 注意：实体转换也放在异步侧，让业务线程只付出一次入队成本
        persistExecutor.submit(() -> repository.save(JobExecutionPO.from(event.record())));
    }
}
