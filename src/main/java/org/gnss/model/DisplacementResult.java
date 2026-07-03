package org.gnss.model;

import java.time.Instant;

/**
 * 位移结果实体
 * <p>单历元GNSS解算结果，包含NEU位移、质量指标、清洗标记和诊断信息</p>
 */
public class DisplacementResult {

    /** 数据ID（格式：YYYYMMDDHHmmSS_XXXX），默认：null */
    private String dataId;

    /** 北向位移（米），默认：0.0 */
    private double dNorth;

    /** 东向位移（米），默认：0.0 */
    private double dEast;

    /** 天向位移（米），默认：0.0 */
    private double dUp;

    /** 解算状态，默认：null */
    private SolutionStatus status;

    /** Ratio值（FIX解的模糊度确认指标），默认：0.0 */
    private double ratio;

    /** 残差RMS（米），默认：0.0 */
    private double rms;
    /** 三维RMS（米），RMS3D = sqrt(sdn^2 + sde^2 + sdu^2)，默认：0.0 */
    private double rms3d;

    /** 位置精度因子PDOP，默认：0.0 */
    private double pdop;

    /** 参与解算的卫星数，默认：0 */
    private int numSatellites;

    /** 时间戳，默认：null */
    private Instant timestamp;

    /** 原始绝对ECEF坐标 [X, Y, Z]（米），默认：null */
    private double[] absoluteEcef;

    /** 是否经过清洗，默认：false */
    private boolean isCleaned;

    /** 是否异常，默认：false */
    private boolean isAbnormal;

    /** 异常原因描述，默认：null */
    private String abnormalReason;

    /** 是否FLOAT降级，默认：false */
    private boolean isDowngraded;

    /** 设备诊断信息（可选），默认：null */
    private DeviceDiagnosis diagnosis;

    public DisplacementResult() {
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
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

    public double getRatio() {
        return ratio;
    }

    public void setRatio(double ratio) {
        this.ratio = ratio;
    }

    public double getRms() {
        return rms;
    }

    public void setRms(double rms) {
        this.rms = rms;
    }
    public double getRms3d() {
        return rms3d;
    }

    public void setRms3d(double rms3d) {
        this.rms3d = rms3d;
    }

    public double getPdop() {
        return pdop;
    }

    public void setPdop(double pdop) {
        this.pdop = pdop;
    }

    public int getNumSatellites() {
        return numSatellites;
    }

    public void setNumSatellites(int numSatellites) {
        this.numSatellites = numSatellites;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public double[] getAbsoluteEcef() {
        return absoluteEcef;
    }

    public void setAbsoluteEcef(double[] absoluteEcef) {
        this.absoluteEcef = absoluteEcef;
    }

    public boolean isCleaned() {
        return isCleaned;
    }

    public void setCleaned(boolean cleaned) {
        isCleaned = cleaned;
    }

    public boolean isAbnormal() {
        return isAbnormal;
    }

    public void setAbnormal(boolean abnormal) {
        isAbnormal = abnormal;
    }

    public String getAbnormalReason() {
        return abnormalReason;
    }

    public void setAbnormalReason(String abnormalReason) {
        this.abnormalReason = abnormalReason;
    }

    public boolean isDowngraded() {
        return isDowngraded;
    }

    public void setDowngraded(boolean downgraded) {
        isDowngraded = downgraded;
    }

    public DeviceDiagnosis getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(DeviceDiagnosis diagnosis) {
        this.diagnosis = diagnosis;
    }
}