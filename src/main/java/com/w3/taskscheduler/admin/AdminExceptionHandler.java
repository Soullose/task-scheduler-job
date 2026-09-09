package com.w3.taskscheduler.admin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * 管理接口异常兜底：并发写造成的唯一键冲突等 → 409；直接 reload 路径遇到 DB 坏数据等 → 400。
 * 其余异常（{@link org.springframework.web.server.ResponseStatusException}、消息体解析等）由框架自带处理。
 */
@Slf4j
@RestControllerAdvice
public class AdminExceptionHandler {

    /** 并发写入/外键等数据完整性冲突（如两个请求同时建同一 id） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("event=admin.db.conflict detail={}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body("数据冲突: " + e.getMostSpecificCause().getMessage());
    }

    /** 直接调用 /api/reload 时若库里存在外部灌入的坏数据，快速失败并给出原因 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("event=admin.bad.data msg={}", e.getMessage());
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
