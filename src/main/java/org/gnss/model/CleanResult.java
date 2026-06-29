package org.gnss.model;

/**
 * 清洗结果
 * <p>包含清洗后的位移结果、通过状态、失败原因和失败层级</p>
 */
public class CleanResult {

    /** 清洗后的位移结果 */
    private DisplacementResult result;

    /** 是否通过清洗，默认：false */
    private boolean passed;

    /** 失败原因描述，默认：null */
    private String failureReason;

    /** 失败层级（1=质量门禁, 2=跳变检测, 3=粗差检测, 4=值替换, 5=基线记忆, 7=综合仲裁），默认：0 */
    private int failureLayer;

    /** 结果是否带有异常标记（即使清洗通过也可能保留），默认：false */
    private boolean resultAbnormal;

    /** 结果异常原因（保留自result.isAbnormal()），默认：null */
    private String resultAbnormalReason;

    /** 是否被第7层综合仲裁修正，默认：false */
    private boolean layer7Corrected;

    /** 第7层加权综合异常分（WCS Score），0~1，越高越可疑，默认：0.0 */
    private double layer7WcsScore;

    /** 第7层可信度分数（1 - wcsScore），0~1，越高越可信，默认：1.0 */
    private double layer7ConfidenceScore = 1.0;

    /** 第7层是否跳过（数据不足/熔断/趋势保护），默认：false */
    private boolean layer7Skipped;

    /** 第7层跳过原因描述，默认：null */
    private String layer7SkipReason;

    /** 第7层趋势保护是否触发（有趋势即放行），默认：false */
    private boolean layer7TrendProtectionTriggered;

    // ========== Layer7中间变量 ==========

    /** 时序残差（Layer3统计窗口偏离度/MAD），默认：0.0 */
    private double timeSeriesResidual;

    /** 空间残差（Layer6与组中位数偏差，米），默认：0.0 */
    private double spatialResidual;

    /** 解算质量归一化值（0~1，1=最优），默认：1.0 */
    private double solutionQuality = 1.0;

    /** 阶跃标记（0或1，1=检测到跳变/阶跃），默认：0.0 */
    private double stepFlag;

    /** 水平变化率（Layer2，当前vs上一合法值，米/历元），默认：0.0 */
    private double horizontalChangeRate;

    /** 垂直变化率（Layer2，当前vs上一合法值，米/历元），默认：0.0 */
    private double verticalChangeRate;

    /** 同向邻居占比（Layer6，组内同向运动的邻居比例，0~1，无空间校验则置1），默认：1.0 */
    private double sameDirectionNeighborRatio = 1.0;

    /** 窗口稳定度（历史40条max-min，米），默认：0.0 */
    private double windowStability;

    /** 北向速度（米/秒），基于当前与上一合法位移差分计算，默认：0.0 */
    private double velocityN;

    /** 东向速度（米/秒），基于当前与上一合法位移差分计算，默认：0.0 */
    private double velocityE;

    /** 天向速度（米/秒），基于当前与上一合法位移差分计算，默认：0.0 */
    private double velocityU;

    /** 北向加速度（米/秒²），基于当前与上一速度差分计算，默认：0.0 */
    private double accelerationN;

    /** 东向加速度（米/秒²），基于当前与上一速度差分计算，默认：0.0 */
    private double accelerationE;

    /** 天向加速度（米/秒²），基于当前与上一速度差分计算，默认：0.0 */
    private double accelerationU;

    public CleanResult() {
    }

    public CleanResult(DisplacementResult result, boolean passed) {
        this.result = result;
        this.passed = passed;
    }

    public DisplacementResult getResult() {
        return result;
    }

    public void setResult(DisplacementResult result) {
        this.result = result;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getFailureLayer() {
        return failureLayer;
    }

    public void setFailureLayer(int failureLayer) {
        this.failureLayer = failureLayer;
    }

    public static CleanResult pass(DisplacementResult result) {
        CleanResult cr = new CleanResult();
        cr.result = result;
        cr.passed = true;
        // 保留result的异常标记，避免信息丢失
        cr.resultAbnormal = result != null && result.isAbnormal();
        cr.resultAbnormalReason = result != null ? result.getAbnormalReason() : null;
        return cr;
    }

    public static CleanResult fail(DisplacementResult result, int layer, String reason) {
        CleanResult cr = new CleanResult();
        cr.result = result;
        cr.passed = false;
        cr.failureLayer = layer;
        cr.failureReason = reason;
        // fail时也保留result的异常标记
        cr.resultAbnormal = result != null && result.isAbnormal();
        cr.resultAbnormalReason = result != null ? result.getAbnormalReason() : null;
        return cr;
    }

    /**
     * 结果是否带有异常标记（即使清洗通过也可能为true）
     * <p>调用方应同时检查 isPassed() 和 isResultAbnormal() 来判断数据质量</p>
     */
    public boolean isResultAbnormal() {
        return resultAbnormal;
    }

    /**
     * 获取结果异常原因（保留自result的abnormalReason）
     */
    public String getResultAbnormalReason() {
        return resultAbnormalReason;
    }

    public boolean isLayer7Corrected() {
        return layer7Corrected;
    }

    public void setLayer7Corrected(boolean layer7Corrected) {
        this.layer7Corrected = layer7Corrected;
    }

    public double getLayer7WcsScore() {
        return layer7WcsScore;
    }

    public void setLayer7WcsScore(double layer7WcsScore) {
        this.layer7WcsScore = layer7WcsScore;
    }

    public double getLayer7ConfidenceScore() {
        return layer7ConfidenceScore;
    }

    public void setLayer7ConfidenceScore(double layer7ConfidenceScore) {
        this.layer7ConfidenceScore = layer7ConfidenceScore;
    }

    public double getTimeSeriesResidual() {
        return timeSeriesResidual;
    }

    public void setTimeSeriesResidual(double timeSeriesResidual) {
        this.timeSeriesResidual = timeSeriesResidual;
    }

    public double getSpatialResidual() {
        return spatialResidual;
    }

    public void setSpatialResidual(double spatialResidual) {
        this.spatialResidual = spatialResidual;
    }

    public double getSolutionQuality() {
        return solutionQuality;
    }

    public void setSolutionQuality(double solutionQuality) {
        this.solutionQuality = solutionQuality;
    }

    public double getStepFlag() {
        return stepFlag;
    }

    public void setStepFlag(double stepFlag) {
        this.stepFlag = stepFlag;
    }

    public boolean isLayer7Skipped() {
        return layer7Skipped;
    }

    public void setLayer7Skipped(boolean layer7Skipped) {
        this.layer7Skipped = layer7Skipped;
    }

    public String getLayer7SkipReason() {
        return layer7SkipReason;
    }

    public void setLayer7SkipReason(String layer7SkipReason) {
        this.layer7SkipReason = layer7SkipReason;
    }

    public boolean isLayer7TrendProtectionTriggered() {
        return layer7TrendProtectionTriggered;
    }

    public void setLayer7TrendProtectionTriggered(boolean layer7TrendProtectionTriggered) {
        this.layer7TrendProtectionTriggered = layer7TrendProtectionTriggered;
    }

    public double getHorizontalChangeRate() {
        return horizontalChangeRate;
    }

    public void setHorizontalChangeRate(double horizontalChangeRate) {
        this.horizontalChangeRate = horizontalChangeRate;
    }

    public double getVerticalChangeRate() {
        return verticalChangeRate;
    }

    public void setVerticalChangeRate(double verticalChangeRate) {
        this.verticalChangeRate = verticalChangeRate;
    }

    public double getSameDirectionNeighborRatio() {
        return sameDirectionNeighborRatio;
    }

    public void setSameDirectionNeighborRatio(double sameDirectionNeighborRatio) {
        this.sameDirectionNeighborRatio = sameDirectionNeighborRatio;
    }

    public double getWindowStability() {
        return windowStability;
    }

    public void setWindowStability(double windowStability) {
        this.windowStability = windowStability;
    }

    public double getVelocityN() {
        return velocityN;
    }

    public void setVelocityN(double velocityN) {
        this.velocityN = velocityN;
    }

    public double getVelocityE() {
        return velocityE;
    }

    public void setVelocityE(double velocityE) {
        this.velocityE = velocityE;
    }

    public double getVelocityU() {
        return velocityU;
    }

    public void setVelocityU(double velocityU) {
        this.velocityU = velocityU;
    }

    public double getAccelerationN() {
        return accelerationN;
    }

    public void setAccelerationN(double accelerationN) {
        this.accelerationN = accelerationN;
    }

    public double getAccelerationE() {
        return accelerationE;
    }

    public void setAccelerationE(double accelerationE) {
        this.accelerationE = accelerationE;
    }

    public double getAccelerationU() {
        return accelerationU;
    }

    public void setAccelerationU(double accelerationU) {
        this.accelerationU = accelerationU;
    }

    /**
     * 提取影子评测特征向量（10维语义特征 + 原始基准数据）
     * <p>
     * F1~F3: N/E/U位移值
     * F4~F5: 水平/垂直变化率
     * F6:    时序标准化残差
     * F7:    空间残差
     * F8:    同向邻居占比
     * F9:    解算质量分
     * F10:   窗口稳定度
     * 原始基准数据: originalN/E/U, velocityN/E/U, accelerationN/E/U
     * </p>
     *
     * @param stationId   测站ID
     * @param epochMillis 历元时间戳
     * @return 影子评测特征向量
     */
    public ShadowFeatureVector extractShadowFeatures(String stationId, long epochMillis) {
        ShadowFeatureVector fv = new ShadowFeatureVector();
        fv.setStationId(stationId);
        fv.setEpochMillis(epochMillis);
        if (result != null) {
            fv.setF1North(result.getdNorth());
            fv.setF2East(result.getdEast());
            fv.setF3Up(result.getdUp());
            fv.setOriginalN(result.getdNorth());
            fv.setOriginalE(result.getdEast());
            fv.setOriginalU(result.getdUp());
        }
        fv.setF4HorizontalChangeRate(horizontalChangeRate);
        fv.setF5VerticalChangeRate(verticalChangeRate);
        fv.setF6TimeSeriesResidual(timeSeriesResidual);
        fv.setF7SpatialResidual(spatialResidual);
        fv.setF8SameDirectionNeighborRatio(sameDirectionNeighborRatio);
        fv.setF9SolutionQuality(solutionQuality);
        fv.setF10WindowStability(windowStability);
        fv.setVelocityN(velocityN);
        fv.setVelocityE(velocityE);
        fv.setVelocityU(velocityU);
        fv.setAccelerationN(accelerationN);
        fv.setAccelerationE(accelerationE);
        fv.setAccelerationU(accelerationU);
        return fv;
    }
}