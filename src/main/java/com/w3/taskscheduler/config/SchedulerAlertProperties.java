package com.w3.taskscheduler.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "scheduler.alert")
public class SchedulerAlertProperties {
    private boolean enabled = true;
    private Duration cooldown = Duration.ofMinutes(10); // 10m
    private Duration evaluateInterval = Duration.ofSeconds(30); // 30s
    private Webhook webhook = new Webhook();

    @Data
    public static class Webhook {
        private String url = "";
        private String secret = "";
        private Duration timeout = Duration.ofSeconds(5); // 5s
        private int retries = 2;
    }

}
