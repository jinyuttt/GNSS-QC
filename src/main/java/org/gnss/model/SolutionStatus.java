package org.gnss.model;

/**
 * 解算状态枚举
 */
public enum SolutionStatus {
    /** 固定解（最优，毫米级精度） */
    FIX,
    /** 浮点解（厘米级精度） */
    FLOAT,
    /** 单点解（米级精度） */
    SINGLE,
    /** 无效解 */
    INVALID
}