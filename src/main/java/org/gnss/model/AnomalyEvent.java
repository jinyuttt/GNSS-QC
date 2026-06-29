package org.gnss.model;

import java.time.Instant;

/**
 * 异常事件
 * <p>记录清洗过程中检测到的异常事件，用于持久化存储和后续追溯</p>
 */
public class AnomalyEvent {

    /** 事件ID，默认：null */
    private String eventId;

    /** 设备ID，默认：null */
    private String deviceId;

    /** 事件类型，默认：null */
    private String eventType;

    /** 事件描述，默认：null */
    private String description;

    /** 关联的dataId，默认：null */
    private String dataId;

    /** 事件时间，默认：null */
    private Instant eventTime;

    /** 清洗层级（1-5），默认：0 */
    private int layer;

    /** 跳变量（米），默认：0.0 */
    private double jumpMagnitude;

    public AnomalyEvent() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public double getJumpMagnitude() {
        return jumpMagnitude;
    }

    public void setJumpMagnitude(double jumpMagnitude) {
        this.jumpMagnitude = jumpMagnitude;
    }
}