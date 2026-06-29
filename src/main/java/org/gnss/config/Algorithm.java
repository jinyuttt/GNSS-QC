package org.gnss.config;

/**
 * 统计粗差检测算法枚举
 */
public enum Algorithm {
    /** 3倍标准差法 */
    THREE_SIGMA,
    /** 四分位距法 */
    IQR,
    /** Hampel标识符法（推荐）：去趋势残差 + |xi - median| > k * MAD */
    HAMPEL
}