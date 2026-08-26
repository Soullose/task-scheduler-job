package com.w3.taskscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "scheduler.monitor")
public class SchedulerMonitorProperties {
    private int warnThreshold = 800; // R1 存活数警戒水位
    private int criticalThreshold = 1500; // R2 存活数危险水位
    private int leakWindowMinutes = 10; // R3 泄漏检测窗口
    private int platformThreadWarn = 60; // R6 平台线程警戒
    private double skipRateWarn = 0.3; // R4 跳过率警戒
    private double timeoutRateWarn = 0.2; // R5a 超时率警戒
    private double timeoutRateCritical = 0.5; // R5b 超时率严重

}
