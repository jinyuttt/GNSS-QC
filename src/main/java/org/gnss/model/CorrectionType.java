package org.gnss.model;

/**
 * 数据修正类型
 */
public enum CorrectionType {

    /** 周期性波动误判为异常 */
    PERIODIC_MISJUDGE,

    /** 温漂误判为异常 */
    THERMAL_MISJUDGE,

    /** 真实形变误判为异常 */
    TREND_MISJUDGE,

    /** 重置ID位置误判 */
    RESET_MISJUDGE
}