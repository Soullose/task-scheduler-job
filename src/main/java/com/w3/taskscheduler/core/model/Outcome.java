package com.w3.taskscheduler.core.model;

public record Outcome(
        ExecutionStatus status, // SUCCESS / FAILED / INTERRUPTED
        int attempts, // 成功时的尝试次数
        String message // 失败原因
) {
    public static Outcome success(int attempts) {
        return new Outcome(ExecutionStatus.SUCCESS, attempts, null);
    }

    public static Outcome interrupted() {
        return new Outcome(ExecutionStatus.INTERRUPTED, 0, "任务被中断");
    }

    public static Outcome failed(String message) {
        return new Outcome(ExecutionStatus.FAILED, 0, message);
    }
}
