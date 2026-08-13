package org.gnss.model;

/**
 * 单层清洗结果
 *
 * <p>每层独立输出，包含通过状态、替换后的值、替换方式和失败原因。
 * 层间通过 LayerResult 链传递，不再共享同一个可变 DisplacementResult。</p>
 *
 * <h3>替换方式</h3>
 * <ul>
 *   <li>{@code null}：未替换，使用原始值</li>
 *   <li>{@code LAST_VALID}：替换为上一个合法值</li>
 *   <li>{@code MEDIAN}：替换为窗口中位数</li>
 *   <li>{@code MEAN}：替换为窗口均值</li>
 *   <li>{@code INITIAL_BASELINE}：替换为初始基线</li>
 * </ul>
 */
public class LayerResult {

    public enum ReplacementMethod {
        LAST_VALID,
        MEDIAN,
        MEAN,
        INITIAL_BASELINE
    }

    private final int layer;
    private final boolean passed;
    private final String reason;

    private final double valueN;
    private final double valueE;
    private final double valueU;

    private final ReplacementMethod replacementMethod;

    private final double stepFlag;

    private LayerResult(Builder builder) {
        this.layer = builder.layer;
        this.passed = builder.passed;
        this.reason = builder.reason;
        this.valueN = builder.valueN;
        this.valueE = builder.valueE;
        this.valueU = builder.valueU;
        this.replacementMethod = builder.replacementMethod;
        this.stepFlag = builder.stepFlag;
    }

    public int getLayer() { return layer; }
    public boolean isPassed() { return passed; }
    public String getReason() { return reason; }
    public double getValueN() { return valueN; }
    public double getValueE() { return valueE; }
    public double getValueU() { return valueU; }
    public ReplacementMethod getReplacementMethod() { return replacementMethod; }
    public double getStepFlag() { return stepFlag; }
    public boolean isReplaced() { return replacementMethod != null; }

    public static Builder builder(int layer) {
        return new Builder(layer);
    }

    public static class Builder {
        private final int layer;
        private boolean passed = true;
        private String reason;
        private double valueN;
        private double valueE;
        private double valueU;
        private ReplacementMethod replacementMethod;
        private double stepFlag;

        public Builder(int layer) {
            this.layer = layer;
        }

        public Builder passed(boolean passed) { this.passed = passed; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder valueN(double v) { this.valueN = v; return this; }
        public Builder valueE(double v) { this.valueE = v; return this; }
        public Builder valueU(double v) { this.valueU = v; return this; }
        public Builder values(double n, double e, double u) { this.valueN = n; this.valueE = e; this.valueU = u; return this; }
        public Builder replacementMethod(ReplacementMethod m) { this.replacementMethod = m; return this; }
        public Builder stepFlag(double f) { this.stepFlag = f; return this; }

        public LayerResult build() {
            return new LayerResult(this);
        }
    }

    @Override
    public String toString() {
        String replace = replacementMethod != null ? replacementMethod.name() : "NONE";
        return String.format("L%d: passed=%s, replace=%s, reason=%s, N=%.4f E=%.4f U=%.4f step=%.0f",
                layer, passed, replace, reason, valueN, valueE, valueU, stepFlag);
    }
}