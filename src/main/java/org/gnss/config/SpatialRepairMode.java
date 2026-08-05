package org.gnss.config;

/**
 * L6 空间校验修复模式枚举
 */
public enum SpatialRepairMode {
    /** 谨慎修复：满足严格条件（方向相反 + 相对MAD超限 + 绝对残差超限 + 同向占比低）才替换 */
    CAUTIOUS,
    /** 完全不修正：L6 仅计算指标，直接透传 L5 结果，不修改任何数据 */
    NO_REPAIR
}