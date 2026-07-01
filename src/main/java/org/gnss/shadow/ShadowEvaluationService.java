package org.gnss.shadow;

import org.gnss.model.ShadowEvaluationResult;
import org.gnss.model.ShadowFeatureVector;

import java.util.List;

/**
 * 影子评测服务接口
 * <p>
 * 独立部署的微服务，与主清洗服务解耦，可有可无，关停不影响主链路。
 * 基于 RRCF + LSTM+Attention 双模型融合架构，核心用于：
 * <ul>
 *   <li>异常识别：区分多径伪形变与真实滑坡形变</li>
 *   <li>数据修复效果研究：生成候选修正值，仅做对比评测，不替换线上正式数据</li>
 *   <li>模型迭代评估：依托线上数据持续做模型增量迭代</li>
 * </ul>
 * </p>
 *
 * <h3>双环路设计</h3>
 * <ul>
 *   <li>环路1：同步实时推理 + 生成候选修正数据（业务评测主链路，SLA ≤50ms）</li>
 *   <li>环路2：5分钟定时增量学习 + 模型自动迭代 + 无感知热加载（模型优化链路）</li>
 * </ul>
 *
 * <h3>实现方案</h3>
 * <ul>
 *   <li>Python服务：RRCF + LSTM+Attention 双模型，HTTP API对外提供推理</li>
 *   <li>存储插件化：支持 Redis / TDengine / MySQL，多项目通用</li>
 * </ul>
 */
public interface ShadowEvaluationService {

    /**
     * 同步批量推理（环路1主链路）
     * <p>
     * 接收批量特征向量，执行 RRCF + LSTM+Attention 并行推理，
     * 对伪形变/噪声数据生成候选修正值，对真实形变仅标记告警。
     * 端到端耗时 ≤50ms。
     * </p>
     *
     * @param features 批量特征向量（含10维特征 + 原始基准数据）
     * @return 批量推理结果（含候选修正值、双模型输出、风险等级等）
     */
    List<ShadowEvaluationResult> inferBatch(List<ShadowFeatureVector> features);

    /**
     * 查询影子表历史结果
     *
     * @param stationId 测站ID
     * @param limit     查询条数
     * @return 影子表记录
     */
    List<ShadowEvaluationResult> queryShadowResults(String stationId, int limit);

    /**
     * 服务是否可用
     *
     * @return true=可用
     */
    boolean isAvailable();
}