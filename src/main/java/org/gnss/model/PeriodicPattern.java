package org.gnss.model;

/**
 * 周期性异常模式枚举
 */
public enum PeriodicPattern {
    /** 日周期漂移（温漂为主） */
    DAILY_DRIFT,
    /** 小时级抖动 */
    HOURLY_JITTER,
    /** 无周期模式 */
    NONE
}