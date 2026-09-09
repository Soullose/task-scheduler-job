package com.w3.taskscheduler.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.w3.taskscheduler.admin.dto.TaskUpsertRequest;
import com.w3.taskscheduler.admin.dto.TaskView;
import com.w3.taskscheduler.core.config.DatabaseTaskConfigLoader;
import com.w3.taskscheduler.core.persistence.entity.SchedulerJobPO;
import com.w3.taskscheduler.core.persistence.repository.SchedulerJobRepository;
import com.w3.taskscheduler.core.scheduler.SchedulerService;

/**
 * {@link TaskAdminService} 管理写路径测试：
 * 只写 DB + 写后自动 reload；写前校验（含 id 冲突/坏数据 400/404）；启停持久化 enable；trigger 委托。
 */
class TaskAdminServiceTests {

    private SchedulerJobRepository repository;
    private DatabaseTaskConfigLoader loader;
    private SchedulerService schedulerService;
    private TaskAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(SchedulerJobRepository.class);
        loader = mock(DatabaseTaskConfigLoader.class);
        schedulerService = mock(SchedulerService.class);
        service = new TaskAdminService(repository, loader, schedulerService);
    }

    // ---------- create ----------

    @Test
    void createWithoutIdGeneratesIdSavesAndReloads() {
        when(repository.existsById(anyString())).thenReturn(false);
        TaskUpsertRequest request = validRequest(null);

        TaskView view = service.create(request);

        assertNotNull(view.id(), "未带 id 应服务端生成");
        ArgumentCaptor<SchedulerJobPO> captor = ArgumentCaptor.forClass(SchedulerJobPO.class);
        verify(repository).save(captor.capture());
        assertEquals(view.id(), captor.getValue().getId());
        assertEquals("db_sync", captor.getValue().getName());
        assertEquals("{\"source\":\"api\",\"batch-size\":100}", captor.getValue().getParams());
        verify(schedulerService).reload();
    }

    @Test
    void createWithDuplicateIdConflicts() {
        when(repository.existsById("dup")).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(validRequest("dup")));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(repository, never()).save(any());
        verify(schedulerService, never()).reload();
    }

    @Test
    void createWithInvalidRowReturnsBadRequestBeforeSave() {
        when(loader.toTaskDefinition(any())).thenThrow(new IllegalArgumentException("cron 非法: bad"));
        TaskUpsertRequest request = validRequest(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(request));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("cron 非法"), e.getReason());
        verify(repository, never()).save(any());
        verify(schedulerService, never()).reload();
    }

    @Test
    void createWritesJsonParamsFromRequestMap() {
        TaskUpsertRequest request = validRequest(null);
        service.create(request);

        ArgumentCaptor<SchedulerJobPO> captor = ArgumentCaptor.forClass(SchedulerJobPO.class);
        verify(repository).save(captor.capture());
        assertEquals("{\"source\":\"api\",\"batch-size\":100}", captor.getValue().getParams());
    }

    // ---------- update / delete ----------

    @Test
    void updateNotFoundReturns404() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.update("missing", validRequest(null)));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(repository, never()).save(any());
        verify(schedulerService, never()).reload();
    }

    @Test
    void updateReplacesRowAndReloads() {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", false)));
        TaskUpsertRequest request = new TaskUpsertRequest(
                null, "renamed", true, null, "0/5 * * * * ?", null, null, null,
                "com.w3.taskscheduler.jobs.handler.TestHandler", "desc", null, 2, null, null, Map.of("k", "v"));

        TaskView view = service.update("t1", request);

        assertEquals("t1", view.id());
        assertEquals("renamed", view.name());
        ArgumentCaptor<SchedulerJobPO> captor = ArgumentCaptor.forClass(SchedulerJobPO.class);
        verify(repository).save(captor.capture());
        assertEquals("t1", captor.getValue().getId());
        assertEquals("renamed", captor.getValue().getName());
        assertEquals(2, captor.getValue().getMaxRetries());
        verify(schedulerService).reload();
    }

    @Test
    void deleteRemovesRowAndReloads() {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", true)));

        service.delete("t1");

        verify(repository).deleteById("t1");
        verify(schedulerService).reload();
    }

    @Test
    void deleteNotFoundReturns404() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.delete("missing"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(repository, never()).deleteById(anyString());
    }

    // ---------- enable/disable：持久化 enable 列 + reload ----------

    @Test
    void setEnabledPersistsEnableColumnAndReloads() {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", false)));

        TaskView view = service.setEnabled("t1", true);

        assertTrue(view.enable());
        ArgumentCaptor<SchedulerJobPO> captor = ArgumentCaptor.forClass(SchedulerJobPO.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().isEnable());
        verify(schedulerService).reload();
    }

    // ---------- trigger / reload ----------

    @Test
    void triggerDelegatesToScheduler() {
        service.trigger("t1");
        verify(schedulerService).triggerTask("t1");
    }

    @Test
    void triggerUnknownTaskReturns404() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("task not found: ghost"))
                .when(schedulerService).triggerTask("ghost");

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.trigger("ghost"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void manualReloadDelegates() {
        service.reload();
        verify(schedulerService).reload();
    }

    @Test
    void reloadFailureAfterWriteSurfaces500() {
        when(loader.toTaskDefinition(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("scheduler stopped"))
                .when(schedulerService).reload();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(validRequest(null)));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        assertTrue(e.getReason().contains("DB 已更新"), e.getReason());
    }

    // ---------- helpers ----------

    private static TaskUpsertRequest validRequest(String id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", "api");
        params.put("batch-size", 100);
        return new TaskUpsertRequest(
                id, "db_sync", true, "cron", "0/5 * * * * ?", null, null, null,
                "com.w3.taskscheduler.jobs.handler.TestHandler", "描述", "60s", 3, "5s", false, params);
    }

    private static SchedulerJobPO row(String id, boolean enable) {
        SchedulerJobPO po = new SchedulerJobPO();
        po.setId(id);
        po.setName("n-" + id);
        po.setEnable(enable);
        po.setCron("0/5 * * * * ?");
        po.setHandler("com.w3.taskscheduler.jobs.handler.TestHandler");
        return po;
    }
}
