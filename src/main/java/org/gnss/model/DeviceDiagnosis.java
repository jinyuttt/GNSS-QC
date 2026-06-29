package org.gnss.model;

/**
 * 设备诊断信息实体
 * <p>包含周期性异常检测、浮动/抖动分析、健康状态评估结果</p>
 */
public class DeviceDiagnosis {

    /** 设备类型，默认：GNSS_MONITOR */
    private DeviceType deviceType = DeviceType.GNSS_MONITOR;

    /** 是否存在周期性异常，默认：false */
    private boolean hasPeriodicAnomaly;

    /** 周期性异常模式，默认：NONE */
    private PeriodicPattern periodicPattern = PeriodicPattern.NONE;

    /** 是否持续浮动/抖动，默认：false */
    private boolean isJittering;

    /** 浮动幅度（米），默认：0.0 */
    private double jitterRange;

    /** 浮动持续时长（秒），默认：0.0 */
    private double jitterDuration;

    /** 恢复正常的数据ID，默认：null */
    private String resetFromId;

    /** 重置原因，默认：null */
    private String resetReason;

    /** 设备健康状态，默认：HEALTHY */
    private HealthStatus health = HealthStatus.HEALTHY;

    // ========== 诊断→清洗反馈信号 ==========

    /** 是否激活温漂模式（温度-位移相关），默认：false */
    private boolean thermalModeActive;

    /** 温度-位移Pearson相关系数（-1~1），默认：0.0 */
    private double thermalCorrelation;

    /** 温漂模式激活时间戳（毫秒），默认：0 */
    private long thermalModeActivatedTime;

    /** 是否处于周期性高波动时段，默认：false */
    private boolean inPeriodicFluctuation;

    /** 周期性波动时段起始分钟（日内分钟），默认：-1（未识别） */
    private int periodicStartMinute = -1;

    /** 周期性波动时段结束分钟（日内分钟），默认：-1（未识别） */
    private int periodicEndMinute = -1;

    /** 反馈信号生成时间戳（毫秒），默认：0 */
    private long feedbackTimestamp;

    public DeviceDiagnosis() {
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public boolean isHasPeriodicAnomaly() {
        return hasPeriodicAnomaly;
    }

    public void setHasPeriodicAnomaly(boolean hasPeriodicAnomaly) {
        this.hasPeriodicAnomaly = hasPeriodicAnomaly;
    }

    public PeriodicPattern getPeriodicPattern() {
        return periodicPattern;
    }

    public void setPeriodicPattern(PeriodicPattern periodicPattern) {
        this.periodicPattern = periodicPattern;
    }

    public boolean isJittering() {
        return isJittering;
    }

    public void setJittering(boolean jittering) {
        isJittering = jittering;
    }

    public double getJitterRange() {
        return jitterRange;
    }

    public void setJitterRange(double jitterRange) {
        this.jitterRange = jitterRange;
    }

    public double getJitterDuration() {
        return jitterDuration;
    }

    public void setJitterDuration(double jitterDuration) {
        this.jitterDuration = jitterDuration;
    }

    public String getResetFromId() {
        return resetFromId;
    }

    public void setResetFromId(String resetFromId) {
        this.resetFromId = resetFromId;
    }

    public String getResetReason() {
        return resetReason;
    }

    public void setResetReason(String resetReason) {
        this.resetReason = resetReason;
    }

    public HealthStatus getHealth() {
        return health;
    }

    public void setHealth(HealthStatus health) {
        this.health = health;
    }

    public boolean isThermalModeActive() {
        return thermalModeActive;
    }

    public void setThermalModeActive(boolean thermalModeActive) {
        this.thermalModeActive = thermalModeActive;
    }

    public double getThermalCorrelation() {
        return thermalCorrelation;
    }

    public void setThermalCorrelation(double thermalCorrelation) {
        this.thermalCorrelation = thermalCorrelation;
    }

    public long getThermalModeActivatedTime() {
        return thermalModeActivatedTime;
    }

    public void setThermalModeActivatedTime(long thermalModeActivatedTime) {
        this.thermalModeActivatedTime = thermalModeActivatedTime;
    }

    public boolean isInPeriodicFluctuation() {
        return inPeriodicFluctuation;
    }

    public void setInPeriodicFluctuation(boolean inPeriodicFluctuation) {
        this.inPeriodicFluctuation = inPeriodicFluctuation;
    }

    public int getPeriodicStartMinute() {
        return periodicStartMinute;
    }

    public void setPeriodicStartMinute(int periodicStartMinute) {
        this.periodicStartMinute = periodicStartMinute;
    }

    public int getPeriodicEndMinute() {
        return periodicEndMinute;
    }

    public void setPeriodicEndMinute(int periodicEndMinute) {
        this.periodicEndMinute = periodicEndMinute;
    }

    public long getFeedbackTimestamp() {
        return feedbackTimestamp;
    }

    public void setFeedbackTimestamp(long feedbackTimestamp) {
        this.feedbackTimestamp = feedbackTimestamp;
    }
}