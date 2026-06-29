package org.gnss.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据修正记录
 * <p>解决"前期判断错误，后期需要修正数据值"的问题</p>
 * <p>当诊断模块发现前期误判时，创建修正记录，业务层据此对对应dataId范围的数据应用修正值</p>
 */
public class DataCorrectionRecord {

    /** 修正ID（格式：YYYYMMDDHHmmSS_XXXX），默认：null */
    private String correctionId;

    /** 设备ID，默认：null */
    private String deviceId;

    /** 修正范围起始dataId，默认：null */
    private String fromDataId;

    /** 修正范围结束dataId，默认：null */
    private String toDataId;

    /** 修正范围起始时间，默认：null */
    private Instant fromTime;

    /** 修正范围结束时间，默认：null */
    private Instant toTime;

    /** 修正类型，默认：null */
    private CorrectionType type;

    /** 原始诊断结论，默认：null */
    private String originalDiagnosis;

    /** 修正后诊断结论，默认：null */
    private String correctedDiagnosis;

    /** 修正原因描述，默认：null */
    private String correctionReason;

    /** 每条数据的具体修正值，默认：空 */
    private List<DataCorrectionItem> corrections = new ArrayList<>();

    /** 修正状态，默认：APPLIED */
    private CorrectionStatus status = CorrectionStatus.APPLIED;

    /** 创建时间，默认：null */
    private Instant createdAt;

    /** 应用时间，默认：null */
    private Instant appliedAt;

    public DataCorrectionRecord() {
    }

    public String getCorrectionId() {
        return correctionId;
    }

    public void setCorrectionId(String correctionId) {
        this.correctionId = correctionId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getFromDataId() {
        return fromDataId;
    }

    public void setFromDataId(String fromDataId) {
        this.fromDataId = fromDataId;
    }

    public String getToDataId() {
        return toDataId;
    }

    public void setToDataId(String toDataId) {
        this.toDataId = toDataId;
    }

    public Instant getFromTime() {
        return fromTime;
    }

    public void setFromTime(Instant fromTime) {
        this.fromTime = fromTime;
    }

    public Instant getToTime() {
        return toTime;
    }

    public void setToTime(Instant toTime) {
        this.toTime = toTime;
    }

    public CorrectionType getType() {
        return type;
    }

    public void setType(CorrectionType type) {
        this.type = type;
    }

    public String getOriginalDiagnosis() {
        return originalDiagnosis;
    }

    public void setOriginalDiagnosis(String originalDiagnosis) {
        this.originalDiagnosis = originalDiagnosis;
    }

    public String getCorrectedDiagnosis() {
        return correctedDiagnosis;
    }

    public void setCorrectedDiagnosis(String correctedDiagnosis) {
        this.correctedDiagnosis = correctedDiagnosis;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public void setCorrectionReason(String correctionReason) {
        this.correctionReason = correctionReason;
    }

    public List<DataCorrectionItem> getCorrections() {
        return corrections;
    }

    public void setCorrections(List<DataCorrectionItem> corrections) {
        this.corrections = corrections;
    }

    public CorrectionStatus getStatus() {
        return status;
    }

    public void setStatus(CorrectionStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }
}