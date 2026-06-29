package org.gnss.config;

import org.gnss.model.DeviceType;

/**
 * 设备诊断配置
 * <p>控制降采样存储、温度驱动采样、周期性异常检测、浮动检测等参数</p>
 */
public class DiagnosisConfig {

    /** 设备类型，默认：GNSS_MONITOR（监测站） */
    public DeviceType deviceType = DeviceType.GNSS_MONITOR;

    /** 常规采样间隔（秒），默认：600（10分钟1条） */
    public int normalIntervalSeconds = 600;

    /** 降采样历史最大容量（条），默认：144（24小时@10分钟） */
    public int maxHistoryCapacity = 144;

    /** 温度变化触发阈值（℃），默认：0.5（日周期模型未成熟时的回退阈值） */
    public double tempChangeThreshold = 0.5;

    /** 温度日周期相对正常模式的偏离阈值（℃），默认：3.0 */
    public double tempDailyDeviationThreshold = 3.0;

    /** 日周期温度剖面槽位数（24小时等分），默认：144（10分钟/槽） */
    public int tempProfileSlots = 144;

    /** 日周期模型初始化所需天数，默认：3（至少3天数据才信任剖面） */
    public int tempProfileInitDays = 3;

    /** 密集记录条数（温度触发时），默认：50 */
    public int denseRecordCount = 50;

    /** 无温度数据时的标记值，默认：-999.0 */
    public double tempDefaultValue = -999.0;

    /** 温度防抖：触发前连续采样数，默认：5 */
    public int tempHysteresisSamples = 5;

    /** 温度EMA平滑系数（0~1），默认：0.3 */
    public double tempEMAFactor = 0.3;

    /** 密集采样后冷却时间（秒），默认：120 */
    public int tempCooldownSeconds = 120;

    /** 趋势检测阈值（米/天），默认：0.001 */
    public double trendThreshold = 0.001;

    /** 浮动/抖动检测阈值（米），默认：0.003 */
    public double jitterThreshold = 0.003;

    /** 最小浮动持续时间（秒），默认：60 */
    public int minJitterDuration = 60;

    /** 异常事件日志环形缓冲容量（条），默认：500 */
    public int anomalyEventLogCapacity = 500;

    // ========== 诊断→清洗反馈联动 ==========

    /** 是否向清洗模块发送反馈信号，默认：true */
    public boolean enableFeedbackToCleaner = true;

    /** 反馈信号更新间隔（秒），默认：60 */
    public int feedbackIntervalSeconds = 60;

    /** 模式切换冷却时间（秒），默认：300 */
    public int modeTransitionCooldown = 300;

    public DiagnosisConfig() {
    }
}