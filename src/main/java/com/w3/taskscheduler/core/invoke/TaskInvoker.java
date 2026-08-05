package com.w3.taskscheduler.core.invoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.model.TaskDefinition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FQCN 类+方法反射执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskInvoker {
    private final ApplicationContext applicationContext;

    public void invoke(TaskDefinition def, TaskContext ctx) throws Exception, InterruptedException {
        String handler = def.handler();
        Class<?> type = Class.forName(handler);
        Object target = resolveTarget(type);
        try {
            Method method = type.getDeclaredMethod("execute", TaskContext.class);
            method.setAccessible(true);
            method.invoke(target, ctx);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error; // Error 不包装，直接上抛
            }
        }
    }

    private Object resolveTarget(Class<?> type) throws Exception {
        try {
            return applicationContext.getBean(type); // 优先 Spring Bean（可注入依赖）
        } catch (BeansException e) {
            Constructor<?> ctor = type.getDeclaredConstructor(); // 兜底：无参构造实例化 POJO
            ctor.setAccessible(true);
            return ctor.newInstance();
        }
    }
}
