package org.gnss.config;

/**
 * 第七层综合仲裁配置
 * <p>所有阈值通过配置类注入，支持默认值</p>
 */
public class Layer7Config {

    // ========== 总开关 ==========

    /** 是否启用第七层综合仲裁，默认：false */
    public boolean enabled = false;

    /** 是否启用影子评测旁路（需第七层启用后才生效），默认：false */
    public boolean enableShadowBranch = false;

    // ========== 历史数据查询 ==========

    /** 最近N条历史记录，默认：40 */
    public int historyLimit = 40;

    /** 不足此条数则跳过判决，默认：3 */
    public int minRecordThreshold = 3;

    // ========== 三项判决条件 ==========

    /** 历史max-min < 此值视为稳定（米），默认：0.02 */
    public double stableThreshold = 0.02;

    /** 斜率绝对值 < 此值视为无趋势，默认：0.001 */
    public double trendThreshold = 0.001;

    /** 偏离阈值 = N × MAD，默认：3.0 */
    public double deviationMultiplier = 3.0;

    // ========== 加权综合异常分（WCS） ==========

    /** 加权分超过此值进入可疑区，默认：0.85 */
    public double scoreThreshold = 0.85;

    /** 时序残差权重，默认：0.35 */
    public double weightTime = 0.35;

    /** 空间残差权重，默认：0.35 */
    public double weightSpace = 0.35;

    /** 解算质量权重，默认：0.20 */
    public double weightQuality = 0.20;

    /** 阶跃标记权重，默认：0.10 */
    public double weightStep = 0.10;

    /** 空间残差归一化阈值（米），默认：0.015 */
    public double spatialResidualNormThreshold = 0.015;

    // ========== 替换值选取 ==========

    /** 替换值选取时，Score < 此值的点才参与中位数计算，默认：0.6 */
    public double replacementScoreThreshold = 0.6;

    /** 替换值选取时，低分点不足此数量则取全部点中位数，默认：3 */
    public int replacementMinLowScoreCount = 3;

    // ========== 趋势保护 ==========

    /** 连续N条同向即触发保护放行，默认：20 */
    public int trendProtectCount = 20;

    // ========== 外部存储 ==========

    /** Redis保留最近N条，默认：200 */
    public int redisRetention = 200;

    /** 查询超时熔断阈值（毫秒），默认：50 */
    public long queryTimeoutMs = 50;

    // ========== 影子评测模式 ==========

    /** 影子评测置信度阈值（生成候选修正值），默认：0.7 */
    public double shadowConfidenceThreshold = 0.7;

    /** 影子评测超长异常区间不生成修正值（历元数），默认：20 */
    public int shadowMaxContinuousErr = 20;

    // ========== 异步变点检测旁路 ==========

    /** 是否启用异步变点检测，默认：false */
    public boolean changePointDetectionEnabled = false;

    /** 变点检测触发间隔（分钟），默认：30 */
    public int changePointScanIntervalMinutes = 30;

    /** 变点检测回看窗口（历元数），默认：60 */
    public int changePointScanWindowSize = 60;

    /** 窗口内最少有效点数，默认：30 */
    public int changePointMinPoints = 30;

    /** 变点前后均值差阈值（米），默认：0.03 */
    public double changePointMinShift = 0.03;

    /** 是否自动应用修正，默认：false */
    public boolean changePointAutoApply = false;

    /** 检测到变点时是否发送告警，默认：true */
    public boolean changePointAlert = true;

    public Layer7Config() {
    }
}