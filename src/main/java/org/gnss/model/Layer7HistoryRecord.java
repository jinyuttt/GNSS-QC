package org.gnss.model;

import java.time.Instant;

/**
 * 第七层历史记录条目
 * <p>第七层仲裁使用的外部存储历史数据模型，由HistoryDataProvider查询返回</p>
 */
public class Layer7HistoryRecord {

    private String stationId;
    private Instant timestamp;
    private double dNorth;
    private double dEast;
    private double dUp;
    private double timeSeriesResidual;
    private double spatialResidual;
    private double solutionQuality;
    private double stepFlag;
    private double wcsScore;
    private boolean layer7Corrected;

    public Layer7HistoryRecord() {
    }

    public String getStationId() { return stationId; }
    public void setStationId(String stationId) { this.stationId = stationId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public double getdNorth() { return dNorth; }
    public void setdNorth(double dNorth) { this.dNorth = dNorth; }
    public double getdEast() { return dEast; }
    public void setdEast(double dEast) { this.dEast = dEast; }
    public double getdUp() { return dUp; }
    public void setdUp(double dUp) { this.dUp = dUp; }
    public double getTimeSeriesResidual() { return timeSeriesResidual; }
    public void setTimeSeriesResidual(double timeSeriesResidual) { this.timeSeriesResidual = timeSeriesResidual; }
    public double getSpatialResidual() { return spatialResidual; }
    public void setSpatialResidual(double spatialResidual) { this.spatialResidual = spatialResidual; }
    public double getSolutionQuality() { return solutionQuality; }
    public void setSolutionQuality(double solutionQuality) { this.solutionQuality = solutionQuality; }
    public double getStepFlag() { return stepFlag; }
    public void setStepFlag(double stepFlag) { this.stepFlag = stepFlag; }
    public double getWcsScore() { return wcsScore; }
    public void setWcsScore(double wcsScore) { this.wcsScore = wcsScore; }
    public boolean isLayer7Corrected() { return layer7Corrected; }
    public void setLayer7Corrected(boolean layer7Corrected) { this.layer7Corrected = layer7Corrected; }
}