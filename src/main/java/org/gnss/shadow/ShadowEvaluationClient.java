package org.gnss.shadow;

import org.gnss.model.CleanResult;
import org.gnss.model.ShadowEvaluationResult;
import org.gnss.model.ShadowFeatureVector;
import org.gnss.shadow.ShadowEvaluationService;

import java.util.ArrayList;
import java.util.List;

/**
 * 第7层影子评测 — 独立调用客户端（连接Python AI服务）
 * <p>
 * 与第6层空间校验（SpatialCheckService）完全独立。
 * 第6层是纯Java统计计算，第7层才调用Python AI服务（RRCF+LSTM+Attention）。
 * 第7层接收第6层或第5层输出数据作为特征向量。
 * </p>
 *
 * <h3>第6层→第7层 数据流</h3>
 * <pre>
 * 第6层 SpatialCheckService.spatialCheck(groupData)
 *   输出：F7(空间残差) + F8(同向邻居占比) + 离群替换值
 *         ↓
 * 第7层 ShadowEvaluationClient.push(cleanResult)
 *   输入：ShadowFeatureVector（含F7/F8，来自第6层或第5层）
 *   输出：RRCF+LSTM双模型推理结果
 * </pre>
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>实时旁路：主链路输出后手动调用 {@link #push(CleanResult, String, long)}</li>
 *   <li>离线回溯：批量注入历史数据 {@link #pushBatch(List, List, List)}</li>
 *   <li>直接注入：已有特征向量直接推理 {@link #infer(ShadowFeatureVector)}</li>
 * </ul>
 *
 * <h3>完整调用示例（第6层 + 第7层）</h3>
 * <pre>{@code
 * // 第6层：空间校验（纯Java，组内所有设备一起传入）
 * SpatialCheckService spatialService = new DefaultSpatialCheckService(0.03, 2);
 * List<SpatialGroupInput> groupData = new ArrayList<>();
 * groupData.add(new SpatialGroupInput("dev-1", 0.012, 0.005, 0.003, "FIX", epochMs));
 * groupData.add(new SpatialGroupInput("dev-2", 0.015, 0.004, 0.002, "FIX", epochMs));
 * groupData.add(new SpatialGroupInput("dev-3", 0.080, 0.006, 0.004, "FIX", epochMs));
 * List<SpatialCheckResult> layer6Results = spatialService.spatialCheck(groupData);
 *
 * // 第7层：影子评测（调用Python AI，单条或批量推理）
 * ShadowEvaluationService shadowService = new HttpShadowEvaluationService("http://localhost:8500");
 * ShadowEvaluationClient shadowClient = new ShadowEvaluationClient(shadowService);
 *
 * for (SpatialCheckResult sr : layer6Results) {
 *     ShadowFeatureVector fv = new ShadowFeatureVector();
 *     fv.setStationId(sr.getDeviceId());
 *     fv.setF1North(sr.isOutlier() ? sr.getReplacedN() : ...);
 *     fv.setF7SpatialResidual(sr.getSpatialResidual());
 *     fv.setF8SameDirectionNeighborRatio(sr.getSameDirectionNeighborRatio());
 *     ShadowEvaluationResult shadow = shadowClient.infer(fv);
 * }
 * }</pre>
 */
public class ShadowEvaluationClient {

    private final ShadowEvaluationService service;

    /**
     * 构造客户端
     *
     * @param service 影子评测服务实现（null则所有方法空操作）
     */
    public ShadowEvaluationClient(ShadowEvaluationService service) {
        this.service = service;
    }

    /**
     * 推送单条清洗结果到影子评测服务
     * <p>
     * 从CleanResult中提取10维特征向量+原始基准数据，同步推理。
     * 服务不可用或异常时静默返回null，不影响调用方。
     * </p>
     *
     * @param cleanResult  清洗结果（5层或6层输出均可）
     * @param stationId    测站ID
     * @param epochMillis  历元时间戳（毫秒）
     * @return 影子评测结果（服务不可用时返回null）
     */
    public ShadowEvaluationResult push(CleanResult cleanResult, String stationId, long epochMillis) {
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
     * 批量推送清洗结果到影子评测服务（离线回溯）
     *
     * @param cleanResults 清洗结果列表
     * @param stationIds   对应测站ID列表
     * @param epochMillis  对应历元时间戳列表
     * @return 影子评测结果列表（不可用或异常的条目为null）
     */
    public List<ShadowEvaluationResult> pushBatch(List<CleanResult> cleanResults,
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
            return inferBatch(features);
        } catch (Exception e) {
            List<ShadowEvaluationResult> results = new ArrayList<>();
            for (int i = 0; i < cleanResults.size(); i++) {
                results.add(null);
            }
            return results;
        }
    }

    /**
     * 直接注入特征向量推理
     * <p>适用于用户自行构造特征向量的场景</p>
     *
     * @param features 10维语义特征向量 + 原始基准数据
     * @return 影子评测结果（服务不可用时返回null）
     */
    public ShadowEvaluationResult infer(ShadowFeatureVector features) {
        if (service == null || !service.isAvailable()) {
            return null;
        }
        try {
            List<ShadowEvaluationResult> results = service.inferBatch(List.of(features));
            return (results != null && !results.isEmpty()) ? results.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量特征向量推理
     *
     * @param features 特征向量列表
     * @return 影子评测结果列表
     */
    public List<ShadowEvaluationResult> inferBatch(List<ShadowFeatureVector> features) {
        if (service == null || !service.isAvailable()) {
            List<ShadowEvaluationResult> results = new ArrayList<>();
            for (int i = 0; i < features.size(); i++) {
                results.add(null);
            }
            return results;
        }
        try {
            return service.inferBatch(features);
        } catch (Exception e) {
            List<ShadowEvaluationResult> results = new ArrayList<>();
            for (int i = 0; i < features.size(); i++) {
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
     * @return 影子表记录（服务不可用时返回空列表）
     */
    public List<ShadowEvaluationResult> queryShadowResults(String stationId, int limit) {
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
}