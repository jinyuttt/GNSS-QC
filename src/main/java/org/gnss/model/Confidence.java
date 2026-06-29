package org.gnss.model;

/**
 * 数据置信度枚举
 * <p>基于空间校验残差与连续回退次数综合评定的数据可信等级</p>
 */
public enum Confidence {
    /** 高置信度：空间残差小，无连续回退 */
    HIGH,
    /** 中置信度：空间残差在阈值边缘，或偶发回退 */
    MEDIUM,
    /** 低置信度：空间残差超限，或频繁回退 */
    LOW
}