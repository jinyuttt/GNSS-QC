package org.gnss.model;

/**
 * 影子评测服务 — 10维语义特征向量 + 原始基准数据
 * <p>
 * 从主链路清洗结果中提取，供 RRCF + LSTM+Attention 双模型推理使用。
 * 特征编号与设计文档一一对应：
 * F1~F3: N/E/U位移值（第6/5层输出）
 * F4~F5: 水平/垂直变化率（第2层）
 * F6:    时序标准化残差（第3层）
 * F7:    空间残差（第6层，无则置0）
 * F8:    同向邻居占比（第6层，无则置1）
 * F9:    解算质量分（第1层）
 * F10:   窗口稳定度（历史40条max-min）
 * </p>
 * <p>
 * 原始基准数据（originalN/E/U, velocityN/E/U, accelerationN/E/U）
 * 作为对比基准存档，不可篡改，用于候选修正值效果评估。
 * </p>
 */
public class ShadowFeatureVector {

    private String stationId;
    private long epochMillis;

    private double f1North;
    private double f2East;
    private double f3Up;
    private double f4HorizontalChangeRate;
    private double f5VerticalChangeRate;
    private double f6TimeSeriesResidual;
    private double f7SpatialResidual;
    private double f8SameDirectionNeighborRatio;
    private double f9SolutionQuality;
    private double f10WindowStability;

    private double originalN;
    private double originalE;
    private double originalU;
    private double velocityN;
    private double velocityE;
    private double velocityU;
    private double accelerationN;
    private double accelerationE;
    private double accelerationU;

    public ShadowFeatureVector() {
        this.f8SameDirectionNeighborRatio = 1.0;
        this.f9SolutionQuality = 1.0;
    }

    public String getStationId() { return stationId; }
    public void setStationId(String stationId) { this.stationId = stationId; }

    public long getEpochMillis() { return epochMillis; }
    public void setEpochMillis(long epochMillis) { this.epochMillis = epochMillis; }

    public double getF1North() { return f1North; }
    public void setF1North(double f1North) { this.f1North = f1North; }

    public double getF2East() { return f2East; }
    public void setF2East(double f2East) { this.f2East = f2East; }

    public double getF3Up() { return f3Up; }
    public void setF3Up(double f3Up) { this.f3Up = f3Up; }

    public double getF4HorizontalChangeRate() { return f4HorizontalChangeRate; }
    public void setF4HorizontalChangeRate(double f4HorizontalChangeRate) { this.f4HorizontalChangeRate = f4HorizontalChangeRate; }

    public double getF5VerticalChangeRate() { return f5VerticalChangeRate; }
    public void setF5VerticalChangeRate(double f5VerticalChangeRate) { this.f5VerticalChangeRate = f5VerticalChangeRate; }

    public double getF6TimeSeriesResidual() { return f6TimeSeriesResidual; }
    public void setF6TimeSeriesResidual(double f6TimeSeriesResidual) { this.f6TimeSeriesResidual = f6TimeSeriesResidual; }

    public double getF7SpatialResidual() { return f7SpatialResidual; }
    public void setF7SpatialResidual(double f7SpatialResidual) { this.f7SpatialResidual = f7SpatialResidual; }

    public double getF8SameDirectionNeighborRatio() { return f8SameDirectionNeighborRatio; }
    public void setF8SameDirectionNeighborRatio(double f8SameDirectionNeighborRatio) { this.f8SameDirectionNeighborRatio = f8SameDirectionNeighborRatio; }

    public double getF9SolutionQuality() { return f9SolutionQuality; }
    public void setF9SolutionQuality(double f9SolutionQuality) { this.f9SolutionQuality = f9SolutionQuality; }

    public double getF10WindowStability() { return f10WindowStability; }
    public void setF10WindowStability(double f10WindowStability) { this.f10WindowStability = f10WindowStability; }

    public double getOriginalN() { return originalN; }
    public void setOriginalN(double originalN) { this.originalN = originalN; }

    public double getOriginalE() { return originalE; }
    public void setOriginalE(double originalE) { this.originalE = originalE; }

    public double getOriginalU() { return originalU; }
    public void setOriginalU(double originalU) { this.originalU = originalU; }

    public double getVelocityN() { return velocityN; }
    public void setVelocityN(double velocityN) { this.velocityN = velocityN; }

    public double getVelocityE() { return velocityE; }
    public void setVelocityE(double velocityE) { this.velocityE = velocityE; }

    public double getVelocityU() { return velocityU; }
    public void setVelocityU(double velocityU) { this.velocityU = velocityU; }

    public double getAccelerationN() { return accelerationN; }
    public void setAccelerationN(double accelerationN) { this.accelerationN = accelerationN; }

    public double getAccelerationE() { return accelerationE; }
    public void setAccelerationE(double accelerationE) { this.accelerationE = accelerationE; }

    public double getAccelerationU() { return accelerationU; }
    public void setAccelerationU(double accelerationU) { this.accelerationU = accelerationU; }

    public double[] toArray() {
        return new double[]{
            f1North, f2East, f3Up,
            f4HorizontalChangeRate, f5VerticalChangeRate,
            f6TimeSeriesResidual, f7SpatialResidual,
            f8SameDirectionNeighborRatio, f9SolutionQuality,
            f10WindowStability
        };
    }

    @Override
    public String toString() {
        return String.format(
            "ShadowFeatureVector{station=%s, epoch=%d, F1=%.4f, F2=%.4f, F3=%.4f, F4=%.6f, F5=%.6f, F6=%.4f, F7=%.4f, F8=%.2f, F9=%.4f, F10=%.6f, orig=[%.4f,%.4f,%.4f], vel=[%.6f,%.6f,%.6f], acc=[%.6f,%.6f,%.6f]}",
            stationId, epochMillis,
            f1North, f2East, f3Up,
            f4HorizontalChangeRate, f5VerticalChangeRate,
            f6TimeSeriesResidual, f7SpatialResidual,
            f8SameDirectionNeighborRatio, f9SolutionQuality,
            f10WindowStability,
            originalN, originalE, originalU,
            velocityN, velocityE, velocityU,
            accelerationN, accelerationE, accelerationU);
    }
}