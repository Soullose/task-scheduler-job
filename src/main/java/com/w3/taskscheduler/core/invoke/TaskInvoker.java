package com.w3.taskscheduler.core.invoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.w3.taskscheduler.core.model.TaskContext;
import com.w3.taskscheduler.core.model.TaskDefinition;
import com.w3.taskscheduler.core.scheduler.ScheduledTaskHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * FQCN 类+方法反射执行
 */
@Slf4j
@Component
public class TaskInvoker {
    /**
     * 容器内全部 ScheduledTaskHandler 实现：beanName -> 实例（含代理）
     */
    private final Map<String, ScheduledTaskHandler> handlers;

    private final ApplicationContext applicationContext;

    private TaskInvoker(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.handlers = applicationContext.getBeansOfType(ScheduledTaskHandler.class);
    }

    public void invoke(TaskDefinition def, TaskContext ctx) throws Exception, InterruptedException {
        String handler = def.handler();
        if (handler == null || handler.isBlank()) {
            throw new IllegalArgumentException("task handler is blank: " + def.name());
        }
        ScheduledTaskHandler matched = findHandler(handler);
        if (matched != null) {
            log.debug("matched scheduled handler bean: {}", handler);
            matched.execute(ctx); // 通道 1：Spring 托管的接口 Bean
            return;
        }
        invokeByReflection(handler, ctx); // 通道 2：未实现接口的类，反射执行
    }

    /**
     * 循环查找匹配
     * 
     * @param handler
     * @return
     */
    private ScheduledTaskHandler findHandler(String handler) {
        return handlers.entrySet().stream()
                .filter(entry -> {
                    ScheduledTaskHandler bean = entry.getValue();
                    String targetClassName = AopUtils.getTargetClass(bean).getName();
                    return handler.equals(targetClassName)
                            || handler.equals(entry.getKey())
                            || handler.equals(bean.getClass().getName());
                })
                .map(entry -> entry.getValue())
                .findFirst()
                .orElse(null);
    }

    private void invokeByReflection(String handler, TaskContext ctx) throws Exception {
        Class<?> type = Class.forName(handler); // FQCN 加载类
        Object target = resolveTarget(type); // 优先 Spring Bean，其次无参构造
        try {
            Method method = type.getDeclaredMethod("execute", TaskContext.class);
            method.setAccessible(true);
            method.invoke(target, ctx);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException ie) { // 保持中断语义
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (cause instanceof Error error) {
                throw error; // Error 不包装直接上抛
            }
            if (cause instanceof Exception ex) {
                throw ex; // 关键修复：业务异常原样上抛
            }
            throw new RuntimeException(cause); // 理论上不可达的兜底
        }
    }

    private Object resolveTarget(Class<?> type) throws Exception {
        try {
            return applicationContext.getBean(type); // 优先 Spring Bean（可注入依赖/AOP）
        } catch (BeansException e) {
            Constructor<?> ctor = type.getDeclaredConstructor(); // 兜底：无参构造 POJO
            ctor.setAccessible(true);
            return ctor.newInstance();
        }
    }
}
