package org.gnss.model;

/**
 * 第6层空间校验 — 单设备输出结果
 * <p>
 * 包含空间残差（F7）、同向邻居占比（F8）和离群替换信息。
 * 当设备被判定为空间离群时，replacedN/E/U 为组中位数替换值。
 * </p>
 */
public class SpatialCheckResult {

    private String deviceId;
    private long epochMillis;

    private double spatialResidual;
    private double sameDirectionNeighborRatio;
    private boolean outlier;
    private String outlierReason;

    private double replacedN;
    private double replacedE;
    private double replacedU;

    private int neighborCount;

    public SpatialCheckResult() {
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public long getEpochMillis() { return epochMillis; }
    public void setEpochMillis(long epochMillis) { this.epochMillis = epochMillis; }

    public double getSpatialResidual() { return spatialResidual; }
    public void setSpatialResidual(double spatialResidual) { this.spatialResidual = spatialResidual; }

    public double getSameDirectionNeighborRatio() { return sameDirectionNeighborRatio; }
    public void setSameDirectionNeighborRatio(double sameDirectionNeighborRatio) { this.sameDirectionNeighborRatio = sameDirectionNeighborRatio; }

    public boolean isOutlier() { return outlier; }
    public void setOutlier(boolean outlier) { this.outlier = outlier; }

    public String getOutlierReason() { return outlierReason; }
    public void setOutlierReason(String outlierReason) { this.outlierReason = outlierReason; }

    public double getReplacedN() { return replacedN; }
    public void setReplacedN(double replacedN) { this.replacedN = replacedN; }

    public double getReplacedE() { return replacedE; }
    public void setReplacedE(double replacedE) { this.replacedE = replacedE; }

    public double getReplacedU() { return replacedU; }
    public void setReplacedU(double replacedU) { this.replacedU = replacedU; }

    public int getNeighborCount() { return neighborCount; }
    public void setNeighborCount(int neighborCount) { this.neighborCount = neighborCount; }
}