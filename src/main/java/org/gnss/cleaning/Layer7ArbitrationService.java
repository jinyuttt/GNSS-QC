package org.gnss.cleaning;

import org.gnss.model.CleanResult;
import org.gnss.model.DisplacementResult;
import org.gnss.model.Layer7ArbitrationResult;

/**
 * 第七层综合仲裁 — 独立接口
 * <p>
 * 与第6层空间校验（SpatialCheckService）完全独立。
 * 第七层接收第六层或第五层输出数据，执行加权综合异常分（WCS）
 * 计算、基于历史的三项判决和趋势保护。
 * </p>
 *
 * <h3>与第6层的关系</h3>
 * <pre>
 * 第6层 SpatialCheckService.spatialCheck(groupData)
 *   输出：F7(空间残差) + F8(同向邻居占比) + 离群替换值
 *       ↓
 * 第7层 Layer7ArbitrationService.arbitrate(...)
 *   输入：位移结果 + 清洗结果 + 时序残差 + 空间残差 + 解算质量 + 阶跃标记
 *   输出：仲裁结果（含WCS分数、是否替换、趋势保护等）
 * </pre>
 *
 * <h3>调用方式</h3>
 * <pre>{@code
 * Layer7ArbitrationService l7Service = new Layer7Arbitrator(layer7Config, historyProvider);
 *
 * // 独立调用第七层仲裁
 * Layer7ArbitrationResult result = l7Service.arbitrate(
 *     displacementResult, cleanResult,
 *     0.5, 0.02, 0.9, 0.0, "station-001"
 * );
 * if (result.isLayer7Corrected()) {
 *     System.out.println("L7替换: " + result.getReplacementSource());
 * }
 *
 * // 仅计算WCS分数（不执行仲裁判决）
 * double wcs = l7Service.computeWcs(0.5, 0.02, 0.9, 0.0);
 * }</pre>
 */
public interface Layer7ArbitrationService {

    /**
     * 执行第七层综合仲裁
     * <p>
     * 完整流程：
     * <ol>
     *   <li>计算加权综合异常分（WCS）</li>
     *   <li>Score ≤ 阈值 → 直接放行</li>
     *   <li>Score > 阈值 → 查询历史数据（带熔断保护）</li>
     *   <li>数据不足 → 跳过判决，直接放行</li>
     *   <li>趋势保护检查 → 有连续同向趋势则无条件放行</li>
     *   <li>三项判决（历史稳定性 + 历史趋势性 + 当前偏离度）</li>
     *   <li>三项全满足 → 执行替换（历史低分点中位数）</li>
     *   <li>任一不满足 → 原样输出</li>
     * </ol>
     * </p>
     *
     * @param result              第6层输出的位移结果
     * @param cleanResult         第6层输出的清洗结果
     * @param timeSeriesResidual  第3层时序残差
     * @param spatialResidual     第6层空间残差
     * @param solutionQuality     解算质量归一化值（0~1，1=最优）
     * @param stepFlag            阶跃标记（0或1）
     * @param stationId           测点ID
     * @return 第七层仲裁结果
     */
    Layer7ArbitrationResult arbitrate(DisplacementResult result,
                                       CleanResult cleanResult,
                                       double timeSeriesResidual,
                                       double spatialResidual,
                                       double solutionQuality,
                                       double stepFlag,
                                       String stationId);

    /**
     * 计算加权综合异常分（WCS）
     * <p>
     * Score = 0.35 × sigmoid(|时序残差|)
     *       + 0.35 × sigmoid(|空间残差| / 空间归一化阈值)
     *       + 0.20 × (1 - 解算质量归一化值)
     *       + 0.10 × 阶跃标记
     * </p>
     *
     * @param timeSeriesResidual 时序标准化残差（偏离度/MAD）
     * @param spatialResidual    空间残差（米）
     * @param solutionQuality    解算质量归一化值（0~1）
     * @param stepFlag           阶跃标记（0或1）
     * @return WCS分数（0~1）
     */
    double computeWcs(double timeSeriesResidual, double spatialResidual,
                      double solutionQuality, double stepFlag);

    /**
     * 服务是否可用
     *
     * @return true=可用
     */
    boolean isAvailable();

    /**
     * 关闭仲裁器，释放资源（如超时守护线程）
     */
    void shutdown();
}