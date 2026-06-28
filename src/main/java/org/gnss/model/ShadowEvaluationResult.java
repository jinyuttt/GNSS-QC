package org.gnss.model;

/**
 * 影子评测服务 — 推理结果
 * <p>
 * RRCF + LSTM+Attention 双模型融合推理结果。
 * 仅写入独立影子表，不与主业务表交互，永不接入实时判决链路。
 * 候选修正值仅用于效果评估对比，不替换线上正式数据。
 * </p>
 */
public class ShadowEvaluationResult {

    private String stationId;
    private long epochMillis;

    private double originalN;
    private double originalE;
    private double originalU;
    private double originalVelocityN;
    private double originalVelocityE;
    private double originalVelocityU;
    private double originalAccelerationN;
    private double originalAccelerationE;
    private double originalAccelerationU;

    private double candidateN;
    private double candidateE;
    private double candidateU;
    private double candidateVelocityN;
    private double candidateVelocityE;
    private double candidateVelocityU;
    private double candidateAccelerationN;
    private double candidateAccelerationE;
    private double candidateAccelerationU;

    private int haveCandidate;
    private CandidateType candidateType;
    private ReplaceSuggest replaceSuggest;

    private double rrcfScore;
    private String lstmResult;
    private double confidence;

    private RiskLevel riskLevel;
    private DeformType deformType;

    private long inferenceTimeMs;
    private String modelVersion;

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum DeformType {
        PSEUDO_DEFORMATION,
        REAL_DEFORMATION,
        UNCERTAIN
    }

    public enum CandidateType {
        TIME_SERIES_PREDICTION,
        NEIGHBOR_INTERPOLATION,
        NONE
    }

    public enum ReplaceSuggest {
        SUGGEST_REPLACE,
        NOT_SUGGEST_REPLACE,
        MANUAL_REVIEW
    }

    public ShadowEvaluationResult() {}

    public String getStationId() { return stationId; }
    public void setStationId(String stationId) { this.stationId = stationId; }

    public long getEpochMillis() { return epochMillis; }
    public void setEpochMillis(long epochMillis) { this.epochMillis = epochMillis; }

    public double getOriginalN() { return originalN; }
    public void setOriginalN(double originalN) { this.originalN = originalN; }

    public double getOriginalE() { return originalE; }
    public void setOriginalE(double originalE) { this.originalE = originalE; }

    public double getOriginalU() { return originalU; }
    public void setOriginalU(double originalU) { this.originalU = originalU; }

    public double getOriginalVelocityN() { return originalVelocityN; }
    public void setOriginalVelocityN(double originalVelocityN) { this.originalVelocityN = originalVelocityN; }

    public double getOriginalVelocityE() { return originalVelocityE; }
    public void setOriginalVelocityE(double originalVelocityE) { this.originalVelocityE = originalVelocityE; }

    public double getOriginalVelocityU() { return originalVelocityU; }
    public void setOriginalVelocityU(double originalVelocityU) { this.originalVelocityU = originalVelocityU; }

    public double getOriginalAccelerationN() { return originalAccelerationN; }
    public void setOriginalAccelerationN(double originalAccelerationN) { this.originalAccelerationN = originalAccelerationN; }

    public double getOriginalAccelerationE() { return originalAccelerationE; }
    public void setOriginalAccelerationE(double originalAccelerationE) { this.originalAccelerationE = originalAccelerationE; }

    public double getOriginalAccelerationU() { return originalAccelerationU; }
    public void setOriginalAccelerationU(double originalAccelerationU) { this.originalAccelerationU = originalAccelerationU; }

    public double getCandidateN() { return candidateN; }
    public void setCandidateN(double candidateN) { this.candidateN = candidateN; }

    public double getCandidateE() { return candidateE; }
    public void setCandidateE(double candidateE) { this.candidateE = candidateE; }

    public double getCandidateU() { return candidateU; }
    public void setCandidateU(double candidateU) { this.candidateU = candidateU; }

    public double getCandidateVelocityN() { return candidateVelocityN; }
    public void setCandidateVelocityN(double candidateVelocityN) { this.candidateVelocityN = candidateVelocityN; }

    public double getCandidateVelocityE() { return candidateVelocityE; }
    public void setCandidateVelocityE(double candidateVelocityE) { this.candidateVelocityE = candidateVelocityE; }

    public double getCandidateVelocityU() { return candidateVelocityU; }
    public void setCandidateVelocityU(double candidateVelocityU) { this.candidateVelocityU = candidateVelocityU; }

    public double getCandidateAccelerationN() { return candidateAccelerationN; }
    public void setCandidateAccelerationN(double candidateAccelerationN) { this.candidateAccelerationN = candidateAccelerationN; }

    public double getCandidateAccelerationE() { return candidateAccelerationE; }
    public void setCandidateAccelerationE(double candidateAccelerationE) { this.candidateAccelerationE = candidateAccelerationE; }

    public double getCandidateAccelerationU() { return candidateAccelerationU; }
    public void setCandidateAccelerationU(double candidateAccelerationU) { this.candidateAccelerationU = candidateAccelerationU; }

    public int getHaveCandidate() { return haveCandidate; }
    public void setHaveCandidate(int haveCandidate) { this.haveCandidate = haveCandidate; }

    public CandidateType getCandidateType() { return candidateType; }
    public void setCandidateType(CandidateType candidateType) { this.candidateType = candidateType; }

    public ReplaceSuggest getReplaceSuggest() { return replaceSuggest; }
    public void setReplaceSuggest(ReplaceSuggest replaceSuggest) { this.replaceSuggest = replaceSuggest; }

    public double getRrcfScore() { return rrcfScore; }
    public void setRrcfScore(double rrcfScore) { this.rrcfScore = rrcfScore; }

    public String getLstmResult() { return lstmResult; }
    public void setLstmResult(String lstmResult) { this.lstmResult = lstmResult; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public DeformType getDeformType() { return deformType; }
    public void setDeformType(DeformType deformType) { this.deformType = deformType; }

    public long getInferenceTimeMs() { return inferenceTimeMs; }
    public void setInferenceTimeMs(long inferenceTimeMs) { this.inferenceTimeMs = inferenceTimeMs; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ShadowEval{station=").append(stationId);
        sb.append(", epoch=").append(epochMillis);
        sb.append(String.format(", orig=[%.4f,%.4f,%.4f]", originalN, originalE, originalU));
        if (haveCandidate == 1) {
            sb.append(String.format(", cand=[%.4f,%.4f,%.4f]", candidateN, candidateE, candidateU));
            sb.append(", candType=").append(candidateType);
            sb.append(", suggest=").append(replaceSuggest);
        } else {
            sb.append(", haveCandidate=0");
        }
        sb.append(String.format(", rrcf=%.4f, lstm=%s, conf=%.4f", rrcfScore, lstmResult, confidence));
        sb.append(", risk=").append(riskLevel);
        sb.append(", deform=").append(deformType);
        sb.append(", time=").append(inferenceTimeMs).append("ms");
        sb.append(", model=").append(modelVersion);
        sb.append("}");
        return sb.toString();
    }
}