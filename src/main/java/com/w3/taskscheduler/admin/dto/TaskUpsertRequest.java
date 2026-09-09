package com.w3.taskscheduler.admin.dto;

import java.util.Map;

/**
 * 任务增/改请求体（与 {@code t_scheduler_job} 列对齐，snake_case；只写 DB，不写 task.yaml）。
 * <p>
 * 宽松填写与库行语义一致：{@code trigger} 可空自动推断（cron/interval 二选一有值）；
 * {@code interval_mode} 缺省 rate；{@code run_on_startup} 缺省 false；
 * {@code allow_concurrent} 空=跟随全局默认；{@code params} 为 JSON 对象（非对象/非法 → 400）。
 * POST 时 {@code id} 可不传（服务端生成时间序 UUID）；PUT 时以路径 id 为准。
 */
public record TaskUpsertRequest(
        String id,
        String name,
        Boolean enable,
        String trigger,
        String cron,
        String interval,
        String interval_mode,
        Boolean run_on_startup,
        String handler,
        String description,
        String time_out,
        Integer max_retries,
        String retry_delay,
        Boolean allow_concurrent,
        Map<String, Object> params) {
}
