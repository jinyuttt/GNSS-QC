package org.gnss.config;

/**
 * 电离层延迟模型枚举
 */
public enum IonoModel {
    /** 双频消电离层组合（IFLC） */
    IFLC,
    /** 广播Klobuchar模型 */
    BRDC,
    /** 实时估计电离层延迟 */
    EST
}