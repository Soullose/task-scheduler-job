package com.w3.taskscheduler.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.w3.taskscheduler.admin.dto.TaskUpsertRequest;
import com.w3.taskscheduler.admin.dto.TaskView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务管理 REST 接口：查询/增删改/启停/手动触发/手动 reload。
 * <p>
 * 任务数据只写 {@code t_scheduler_job}，不写 task.yaml；增删改与启停成功后自动 reload 立即生效。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TaskAdminController {

    private final TaskAdminService service;

    // ---------- 查询 ----------

    @GetMapping("/api/tasks")
    public List<TaskView> list() {
        return service.list();
    }

    @GetMapping("/api/tasks/{id}")
    public TaskView get(@PathVariable String id) {
        return service.get(id);
    }

    // ---------- 增删改（写 DB + 自动 reload） ----------

    @PostMapping("/api/tasks")
    public ResponseEntity<TaskView> create(@RequestBody TaskUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/api/tasks/{id}")
    public TaskView update(@PathVariable String id, @RequestBody TaskUpsertRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/api/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- 启停（写 DB enable 列 + 自动 reload） / 手动触发（不写库） ----------

    @PostMapping("/api/tasks/{id}/enable")
    public TaskView enable(@PathVariable String id) {
        return service.setEnabled(id, true);
    }

    @PostMapping("/api/tasks/{id}/disable")
    public TaskView disable(@PathVariable String id) {
        return service.setEnabled(id, false);
    }

    @PostMapping("/api/tasks/{id}/trigger")
    public ResponseEntity<Void> trigger(@PathVariable String id) {
        service.trigger(id);
        return ResponseEntity.ok().build();
    }

    // ---------- 手动 reload（DB 无变化时 no-op） ----------

    @PostMapping("/api/reload")
    public ResponseEntity<Void> reload() {
        service.reload();
        return ResponseEntity.ok().build();
    }
}
