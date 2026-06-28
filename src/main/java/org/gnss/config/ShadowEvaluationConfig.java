package org.gnss.config;

/**
 * 影子评测服务配置
 * <p>
 * RRCF + LSTM+Attention 双模型融合影子评测系统配置。
 * 旁路影子试验系统，不接管主业务数据流。
 * </p>
 */
public class ShadowEvaluationConfig {

    public boolean evalMode = true;

    public double confidenceThreshold = 0.7;

    public int maxContinuousErr = 20;

    public String initRrcfModelPath = "./model/rrcf_default";

    public String initLstmModelPath = "./model/lstm_default.pth";

    public String normParamsPath = "./model/norm.params";

    public String snapshotDir = "./model/snapshot";

    public int trainIntervalMin = 5;

    public int trainMaxFetchNum = 10000;

    public int triggerSample = 5000;

    public int triggerRound = 6;

    public String hotReloadMode = "poll";

    public int pollIntervalS = 60;

    public int keepVersions = 15;

    public boolean autoRollback = true;

    public double errorThreshold = 0.15;

    public ShadowEvaluationConfig() {
    }
}