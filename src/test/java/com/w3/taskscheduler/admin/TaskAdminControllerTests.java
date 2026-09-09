package com.w3.taskscheduler.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.w3.taskscheduler.admin.dto.TaskUpsertRequest;
import com.w3.taskscheduler.admin.dto.TaskView;

/**
 * {@link TaskAdminController} 路由与状态码测试（standalone MockMvc + mock 服务层）。
 */
class TaskAdminControllerTests {

    private TaskAdminService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TaskAdminService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskAdminController(service))
                .setControllerAdvice(new AdminExceptionHandler())
                .build();
    }

    @Test
    void listTasks() throws Exception {
        when(service.list()).thenReturn(List.of(view("t1")));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk());
        verify(service).list();
    }

    @Test
    void getTask() throws Exception {
        when(service.get("t1")).thenReturn(view("t1"));

        mockMvc.perform(get("/api/tasks/t1"))
                .andExpect(status().isOk());
        verify(service).get("t1");
    }

    @Test
    void getMissingTaskReturns404() throws Exception {
        when(service.get("ghost")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: ghost"));

        mockMvc.perform(get("/api/tasks/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createTaskReturns201AndParsesSnakeBody() throws Exception {
        TaskView created = view("t1");
        when(service.create(org.mockito.ArgumentMatchers.any())).thenReturn(created);
        String body = """
                {"id":"t1","name":"db_sync","enable":true,"trigger":"cron","cron":"0/5 * * * * ?",
                 "handler":"com.w3.taskscheduler.jobs.handler.TestHandler","time_out":"60s","max_retries":3,
                 "params":{"source":"api"}}
                """;

        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        org.mockito.ArgumentCaptor<TaskUpsertRequest> captor =
                org.mockito.ArgumentCaptor.forClass(TaskUpsertRequest.class);
        verify(service).create(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("db_sync", captor.getValue().name());
    }

    @Test
    void createTaskWithBadRowReturns400() throws Exception {
        when(service.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "handler 不能为空"));
        String body = "{\"name\":\"x\"}";

        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        when(service.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "任务已存在: dup"));
        String body = "{\"id\":\"dup\",\"name\":\"x\",\"handler\":\"h\"}";

        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void updateTaskReturns200() throws Exception {
        when(service.update(org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(view("t1"));
        String body = "{\"name\":\"new_name\",\"enable\":true,\"trigger\":\"interval\",\"interval\":\"200s\",\"handler\":\"h\"}";

        mockMvc.perform(put("/api/tasks/t1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(service).update(org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteTaskReturns204() throws Exception {
        mockMvc.perform(delete("/api/tasks/t1"))
                .andExpect(status().isNoContent());
        verify(service).delete("t1");
    }

    @Test
    void enableAndDisableReturn200() throws Exception {
        when(service.setEnabled("t1", true)).thenReturn(view("t1"));
        when(service.setEnabled("t1", false)).thenReturn(view("t1"));

        mockMvc.perform(post("/api/tasks/t1/enable")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/t1/disable")).andExpect(status().isOk());
        verify(service).setEnabled("t1", true);
        verify(service).setEnabled("t1", false);
    }

    @Test
    void triggerTaskReturns200() throws Exception {
        mockMvc.perform(post("/api/tasks/t1/trigger")).andExpect(status().isOk());
        verify(service).trigger("t1");
    }

    @Test
    void manualReloadReturns200() throws Exception {
        mockMvc.perform(post("/api/reload")).andExpect(status().isOk());
        verify(service).reload();
    }

    private static TaskView view(String id) {
        return new TaskView(id, "db_sync", true, "cron", "0/5 * * * * ?", null, null, null,
                "com.w3.taskscheduler.jobs.handler.TestHandler", null, "60s", 3, null, null, null);
    }
}
