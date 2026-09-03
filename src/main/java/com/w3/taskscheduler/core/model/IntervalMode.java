package com.w3.taskscheduler.core.model;

/**
 * interval（固定间隔）调度模式，对应 Spring {@code PeriodicTrigger} 的两种推进方式：
 * <ul>
 * <li>{@link #RATE}：fixed-rate——按“上一次计划触发时刻 + interval”推进，节奏恒定、不因执行耗时漂移
 *     （类似 cron 的到点触发；本调度器默认值）；</li>
 * <li>{@link #DELAY}：fixed-delay——按“上一次执行完成时刻 + interval”推进。
 *     注意：本调度器中业务在虚拟线程异步执行，触发 Runnable 只负责提交（微秒级），
 *     PeriodicTrigger 感知的“完成”是提交完成而非业务完成，因此 DELAY 只相对提交时刻生效，
 *     防止业务重叠仍由并发闸门（allow-concurrent=false）负责。</li>
 * </ul>
 * 仅对配置了 {@code interval} 的任务有效；与 cron 任务互斥。
 */
public enum IntervalMode {

    /** fixed-rate：每次 = 上一次计划触发时刻 + interval（默认，未配置时按此处理） */
    RATE,

    /** fixed-delay：每次 = 上一次完成时刻 + interval */
    DELAY
}
