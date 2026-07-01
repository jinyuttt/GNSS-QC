 package org.gnss.cleaning;

import org.gnss.config.Layer7Config;
import org.gnss.model.CleanResult;
import org.gnss.model.ShadowEvaluationResult;
import org.gnss.model.ShadowFeatureVector;
import org.gnss.shadow.ShadowEvaluationService;

import java.util.ArrayList;
import java.util.List;

/**
 * 影子评测模式
 * <p>
 * 旁路影子试验系统，不接管主业务数据流，核心用于：
 * 异常识别、数据修复效果研究、模型迭代评估。
 * 基于 RRCF + LSTM+Attention 双模型融合架构。
 * 候选修正值仅做对比评测，不替换线上正式数据。
 * </p>
 *
 * <h3>双环路设计</h3>
 * <ul>
 *   <li>环路1：同步实时推理 + 生成候选修正数据（业务评测主链路，SLA ≤50ms）</li>
 *   <li>环路2：5分钟定时增量学习 + 模型自动迭代 + 无感知热加载（模型优化链路，Python侧实现）</li>
 * </ul>
 *
 * <h3>异常判定规则（双模型联合校验）</h3>
 * <pre>
 * 同时满足以下条件，生成候选修正数据：
 *   RRCF：识别为数据分布突变、连续跳变
 *   LSTM+Attention：时序特征判定为非真实形变
 *   综合置信度 ≥ 配置阈值（默认0.7）
 *
 * 判定为真实滑坡形变：不生成修正值，仅标记告警
 * 结论冲突/置信度不足：标记为待人工复核，不生成修正值
 * </pre>
 *
 * <h3>数据修复算法</h3>
 * <ul>
 *   <li>单点异常：LSTM时序预测为主、邻域插值为辅</li>
 *   <li>连续短时异常（≤20历元）：基于正常时序曲线做平滑修正</li>
 *   <li>长时段信号失锁（＞20历元）：标记数据无效，不做插值修正</li>
 * </ul>
 */
public class ShadowEvaluationMode {

    private final Layer7Config config;
    private final ShadowEvaluationService service;

    /**
     * 构造影子评测模式
     *
     * @param config   第七层配置
     * @param service  影子评测服务实现（null则所有推理返回null）
     */
    public ShadowEvaluationMode(Layer7Config config, ShadowEvaluationService service) {
        this.config = config;
        this.service = service;
    }

    /**
     * 对单条清洗结果执行影子评测推理
     * <p>
     * 从CleanResult中提取特征向量，同步调用影子评测服务。
     * 服务不可用或异常时静默返回null，不影响调用方。
     * </p>
     *
     * @param cleanResult  清洗结果
     * @param stationId    测站ID
     * @param epochMillis  历元时间戳
     * @return 影子评测结果（服务不可用时返回null）
     */
    public ShadowEvaluationResult evaluate(CleanResult cleanResult, String stationId, long epochMillis) {
        if (service == null || !service.isAvailable()) {
            return null;
        }
        try {
            ShadowFeatureVector features = cleanResult.extractShadowFeatures(stationId, epochMillis);
            List<ShadowEvaluationResult> results = service.inferBatch(List.of(features));
            return (results != null && !results.isEmpty()) ? results.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量推理（用于离线回溯历史数据）
     *
     * @param cleanResults 清洗结果列表
     * @param stationIds   测站ID列表
     * @param epochMillis  历元时间戳列表
     * @return 影子评测结果列表
     */
    public List<ShadowEvaluationResult> batchEvaluate(List<CleanResult> cleanResults,
                                                       List<String> stationIds,
                                                       List<Long> epochMillis) {
        if (service == null || !service.isAvailable()) {
            List<ShadowEvaluationResult> results = new ArrayList<>();
            for (int i = 0; i < cleanResults.size(); i++) {
                results.add(null);
            }
            return results;
        }
        try {
            List<ShadowFeatureVector> features = new ArrayList<>();
            for (int i = 0; i < cleanResults.size(); i++) {
                CleanResult cr = cleanResults.get(i);
                String sid = (i < stationIds.size()) ? stationIds.get(i) : "unknown";
                long ts = (i < epochMillis.size()) ? epochMillis.get(i) : 0L;
                features.add(cr.extractShadowFeatures(sid, ts));
            }
            return service.inferBatch(features);
        } catch (Exception e) {
            List<ShadowEvaluationResult> results = new ArrayList<>();
            for (int i = 0; i < cleanResults.size(); i++) {
                results.add(null);
            }
            return results;
        }
    }

    /**
     * 查询影子表历史结果
     *
     * @param stationId 测站ID
     * @param limit     查询条数
     * @return 影子表记录
     */
    public List<ShadowEvaluationResult> queryResults(String stationId, int limit) {
        if (service == null || !service.isAvailable()) {
            return new ArrayList<>();
        }
        try {
            return service.queryShadowResults(stationId, limit);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 服务是否可用
     *
     * @return true=可用
     */
    public boolean isAvailable() {
        return service != null && service.isAvailable();
    }

    /**
     * 获取置信度阈值
     *
     * @return 置信度阈值
     */
    public double getConfidenceThreshold() {
        return config.shadowConfidenceThreshold;
    }

    /**
     * 获取超长异常区间阈值
     *
     * @return 历元数
     */
    public int getMaxContinuousErr() {
        return config.shadowMaxContinuousErr;
    }
}