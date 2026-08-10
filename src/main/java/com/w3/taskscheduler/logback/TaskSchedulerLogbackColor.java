package com.w3.taskscheduler.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

public class TaskSchedulerLogbackColor extends ForegroundCompositeConverterBase<ILoggingEvent> {

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        switch (event.getLevel().toInt()) {
            case Level.ERROR_INT:
                return ANSIConstants.BOLD + ANSIConstants.RED_FG; // 红色加粗
            case Level.WARN_INT:
                return ANSIConstants.BOLD + ANSIConstants.YELLOW_FG; // 黄色加粗
            case Level.INFO_INT:
                return ANSIConstants.GREEN_FG; // 绿色
            case Level.DEBUG_INT:
                return ANSIConstants.BOLD + ANSIConstants.MAGENTA_FG; // 紫色加粗
            default:
                return ANSIConstants.DEFAULT_FG;
        }
    }

}
