package org.gnss.model;

/**
 * 第七层综合仲裁结果
 * <p>包含最终位移值、可信度分数和仲裁过程标记</p>
 */
public class Layer7ArbitrationResult {

    /** 清洗后的位移结果（可能被第7层替换） */
    private DisplacementResult result;

    /** 加权综合异常分（WCS Score），0~1，越高越可疑 */
    private double wcsScore;

    /** 可信度分数（1 - wcsScore），0~1，越高越可信 */
    private double confidenceScore;

    /** 是否被第7层修正替换 */
    private boolean layer7Corrected;

    /** 第7层是否跳过（数据不足/熔断/趋势保护） */
    private boolean layer7Skipped;

    /** 跳过原因描述 */
    private String skipReason;

    /** 趋势保护是否触发（有趋势即放行） */
    private boolean trendProtectionTriggered;

    /** 历史稳定性判定（窗口内max-min < stableThreshold） */
    private boolean historicallyStable;

    /** 历史趋势性判定（斜率绝对值 < trendThreshold） */
    private boolean historicallyNoTrend;

    /** 当前偏离度判定（偏差 > deviationMultiplier × MAD） */
    private boolean currentlyDeviant;

    /** 替换值来源描述 */
    private String replacementSource;

    public Layer7ArbitrationResult() {
    }

    public static Layer7ArbitrationResult passthrough(DisplacementResult result, double wcsScore) {
        Layer7ArbitrationResult r = new Layer7ArbitrationResult();
        r.result = result;
        r.wcsScore = wcsScore;
        r.confidenceScore = 1.0 - wcsScore;
        r.layer7Corrected = false;
        return r;
    }

    public static Layer7ArbitrationResult skipped(DisplacementResult result, double wcsScore, String reason) {
        Layer7ArbitrationResult r = new Layer7ArbitrationResult();
        r.result = result;
        r.wcsScore = wcsScore;
        r.confidenceScore = 1.0 - wcsScore;
        r.layer7Corrected = false;
        r.layer7Skipped = true;
        r.skipReason = reason;
        return r;
    }

    public DisplacementResult getResult() {
        return result;
    }

    public void setResult(DisplacementResult result) {
        this.result = result;
    }

    public double getWcsScore() {
        return wcsScore;
    }

    public void setWcsScore(double wcsScore) {
        this.wcsScore = wcsScore;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public boolean isLayer7Corrected() {
        return layer7Corrected;
    }

    public void setLayer7Corrected(boolean layer7Corrected) {
        this.layer7Corrected = layer7Corrected;
    }

    public boolean isLayer7Skipped() {
        return layer7Skipped;
    }

    public void setLayer7Skipped(boolean layer7Skipped) {
        this.layer7Skipped = layer7Skipped;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public boolean isTrendProtectionTriggered() {
        return trendProtectionTriggered;
    }

    public void setTrendProtectionTriggered(boolean trendProtectionTriggered) {
        this.trendProtectionTriggered = trendProtectionTriggered;
    }

    public boolean isHistoricallyStable() {
        return historicallyStable;
    }

    public void setHistoricallyStable(boolean historicallyStable) {
        this.historicallyStable = historicallyStable;
    }

    public boolean isHistoricallyNoTrend() {
        return historicallyNoTrend;
    }

    public void setHistoricallyNoTrend(boolean historicallyNoTrend) {
        this.historicallyNoTrend = historicallyNoTrend;
    }

    public boolean isCurrentlyDeviant() {
        return currentlyDeviant;
    }

    public void setCurrentlyDeviant(boolean currentlyDeviant) {
        this.currentlyDeviant = currentlyDeviant;
    }

    public String getReplacementSource() {
        return replacementSource;
    }

    public void setReplacementSource(String replacementSource) {
        this.replacementSource = replacementSource;
    }
}