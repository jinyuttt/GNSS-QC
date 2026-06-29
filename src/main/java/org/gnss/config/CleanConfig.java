package org.gnss.config;

/**
 * 六层清洗配置
 * <p>所有阈值通过配置类注入，支持默认值</p>
 */
public class CleanConfig {

    // ========== 第一层：质量门禁 ==========

    /** 最小卫星数，默认：6 */
    public int minSatellite = 6;

    /** 最大PDOP（FIX解），默认：4.0 */
    public double maxPdop = 4.0;

    /** 最大PDOP（FLOAT解），默认：6.0 */
    public double maxPdopFloat = 6.0;

    /** 最大RMS（FIX解，米），默认：0.05 */
    public double maxRms = 0.05;

    /** 最大RMS（FLOAT解，米），默认：0.15 */
    public double maxRmsFloat = 0.15;

    /** 最小Ratio（FIX解），默认：3.0 */
    public double minRatio = 3.0;

    // ========== 第二层：相邻跳变检测 ==========

    /** 水平方向最大跳变阈值（米），默认：0.05 */
    public double maxStepHorizontal = 0.05;

    /** 高程方向最大跳变阈值（米），默认：0.08 */
    public double maxStepVertical = 0.08;

    /** 是否启用同向趋势快速通道，默认：true */
    public boolean trendQuickReleaseEnabled = true;

    /** 连续同向跳变历元数阈值，默认：3 */
    public int trendQuickReleaseCount = 3;

    /** 同向趋势最大时间窗口（秒），0表示不限制，默认：10 */
    public int trendQuickReleaseMaxSeconds = 10;

    // ========== 第三层：滑动窗口统计粗差检测 ==========

    /** 滑动窗口大小，默认：20 */
    public int windowSize = 20;

    /** 统计粗差检测算法，默认：HAMPEL（去趋势残差 + Hampel标识符） */
    public Algorithm algorithm = Algorithm.HAMPEL;

    /** 高程方向容忍系数（IQR倍数），默认：1.5 */
    public double verticalToleranceFactor = 1.5;

    /** 窗口未满时全局经验标准差（米），默认：0.01 */
    public double windowInitFallbackSigma = 0.01;

    /** Hampel标识符阈值系数k，|xi - median| > k * MAD 视为粗差，默认：3.0 */
    public double hampelK = 3.0;

    /** Hampel高程方向阈值系数k（通常比水平方向宽松），默认：3.5 */
    public double hampelKVertical = 3.5;

    // ========== 第五层：长期基线记忆 ==========

    /** 快速基线窗口大小（5分钟@1Hz），默认：300 */
    public int longTermWindowSize = 300;

    /** 慢速基线窗口大小（条数），用于24小时级长期对比，默认：1440 */
    public int slowBaselineSize = 1440;

    /** 阶跃观察历元数（确认阶跃所需连续稳定历元），默认：60 */
    public int stepObservationCount = 60;

    /** 阶跃稳定确认窗口（秒），默认：60 */
    public int stepConfirmWindow = 60;

    /** 阶跃分析触发阈值（米），默认：0.03 */
    public double stepDeviationThreshold = 0.03;

    /** 阶跃稳定阈值（米），候选位置漂移在此范围内视为稳定，默认：0.003 */
    public double stepStableThreshold = 0.003;

    /** 阶跃确认阈值（米），快慢基线差异小于此值视为确认，默认：0.003 */
    public double stepConfirmThreshold = 0.003;

    /** 阶跃观察最大超时（秒），超时后放弃观察，默认：120 */
    public int stepMaxTimeoutSeconds = 120;

    // ========== 诊断反馈联动：动态阈值自适应 ==========

    /** 是否启用动态阈值自适应，默认：true */
    public boolean adaptiveThresholdEnabled = true;

    /** 温漂模式下水平跳变阈值缩放系数，默认：1.6 */
    public double thermalModeScaleHorizontal = 1.6;

    /** 温漂模式下高程跳变阈值缩放系数，默认：1.5 */
    public double thermalModeScaleVertical = 1.5;

    /** 温漂模式下IQR容忍系数缩放倍数，默认：2.0 */
    public double thermalIqrScaleMultiplier = 2.0;

    /** 温度持续变化多久后激活温漂模式（分钟），默认：10 */
    public int thermalModeActivationMinutes = 10;

    /** 温度-位移相关系数阈值（超过则激活温漂模式），默认：0.7 */
    public double thermalCorrelationThreshold = 0.7;

    /** 周期性模式激活自相关系数阈值，默认：0.5 */
    public double periodicModeActivationCorrelation = 0.5;

    // ========== 第六层：空间联合校验 ==========

    /** 是否启用空间联合校验，默认：false */
    public boolean enableSpatialCheck = false;

    /** 空间离群阈值（米），当前设备与组中位数偏差超过此值视为离群，默认：0.03 */
    public double spatialOutlierThreshold = 0.03;

    /** 空间校验最少邻居设备数，默认：2 */
    public int spatialMinNeighbors = 2;

    public CleanConfig() {
    }
}