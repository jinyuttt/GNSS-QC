package org.gnss.model;

import java.time.Instant;

/**
 * 降采样历史条目
 * <p>用于存储降采样后的位移数据（10分钟1条），支持长期趋势分析</p>
 */
public class HistoryEntry {

    /** 数据ID，默认：null */
    private String dataId;

    /** 时间戳，默认：null */
    private Instant timestamp;

    /** 北向位移（米），默认：0.0 */
    private double dNorth;

    /** 东向位移（米），默认：0.0 */
    private double dEast;

    /** 天向位移（米），默认：0.0 */
    private double dUp;

    /** 解算状态，默认：null */
    private SolutionStatus status;

    /** 是否异常，默认：false */
    private boolean abnormal;

    /** 温度值（℃），默认：-999.0 */
    private double temperature;

    public HistoryEntry() {
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public double getdNorth() {
        return dNorth;
    }

    public void setdNorth(double dNorth) {
        this.dNorth = dNorth;
    }

    public double getdEast() {
        return dEast;
    }

    public void setdEast(double dEast) {
        this.dEast = dEast;
    }

    public double getdUp() {
        return dUp;
    }

    public void setdUp(double dUp) {
        this.dUp = dUp;
    }

    public SolutionStatus getStatus() {
        return status;
    }

    public void setStatus(SolutionStatus status) {
        this.status = status;
    }

    public boolean isAbnormal() {
        return abnormal;
    }

    public void setAbnormal(boolean abnormal) {
        this.abnormal = abnormal;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}