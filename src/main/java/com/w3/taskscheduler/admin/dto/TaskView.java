package com.w3.taskscheduler.admin.dto;

/**
 * 任务视图（DB 行投影，snake_case 与表列对齐）。
 *
 * @param params 解析后的 JSON（对象/标量），原始文本非法时按原始文本返回，空为 null
 */
public record TaskView(
        String id,
        String name,
        boolean enable,
        String trigger,
        String cron,
        String interval,
        String interval_mode,
        Boolean run_on_startup,
        String handler,
        String description,
        String time_out,
        int max_retries,
        String retry_delay,
        Boolean allow_concurrent,
        Object params) {
}
