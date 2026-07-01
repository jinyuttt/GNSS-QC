package org.gnss.cleaning;

import org.gnss.model.SpatialCheckResult;
import org.gnss.model.SpatialGroupInput;

import java.util.List;

/**
 * 第6层空间一致性校验 — 独立接口
 * <p>
 * 与第7层影子评测（ShadowEvaluationService）完全独立。
 * 第6层需要把整个组的设备数据一起传入（List），因为空间校验
 * 依赖邻居设备数据做组中位数对比和离群检测。
 * </p>
 *
 * <h3>与第7层的关系</h3>
 * <pre>
 * 第6层空间校验 →
 *   输出：F7(空间残差) + F8(同向邻居占比) + 离群替换值
 *       ↓
 * 第7层影子评测 ← 接收第6层或第5层输出数据
 *   输入：ShadowFeatureVector（含F7/F8）
 *   输出：RRCF+LSTM双模型推理结果
 * </pre>
 *
 * <h3>调用方式</h3>
 * <pre>{@code
 * SpatialCheckService spatialService = new DefaultSpatialCheckService(0.03, 2);
 * List<SpatialGroupInput> groupData = new ArrayList<>();
 * groupData.add(new SpatialGroupInput("dev-1", 0.012, 0.005, 0.003, "FIX", epochMs));
 * groupData.add(new SpatialGroupInput("dev-2", 0.015, 0.004, 0.002, "FIX", epochMs));
 * groupData.add(new SpatialGroupInput("dev-3", 0.080, 0.006, 0.004, "FIX", epochMs));
 *
 * List<SpatialCheckResult> results = spatialService.spatialCheck(groupData);
 * for (SpatialCheckResult r : results) {
 *     System.out.println(r.getDeviceId() + " F7=" + r.getSpatialResidual()
 *         + " F8=" + r.getSameDirectionNeighborRatio()
 *         + " outlier=" + r.isOutlier());
 * }
 * }</pre>
 */
public interface SpatialCheckService {

    /**
     * 批量空间校验（第6层核心接口）
     * <p>
     * 将整个组的设备数据一起传入，对每个设备：
     * <ol>
     *   <li>计算与组内其他设备中位数的偏差（空间残差 F7）</li>
     *   <li>计算组内同向运动邻居占比（F8）</li>
     *   <li>偏差超过阈值的标记为空间离群，返回组中位数替换值</li>
     * </ol>
     * 需要至少 minNeighbors 个邻居才执行校验，否则 F7=0, F8=1.0, outlier=false。
     * </p>
     *
     * @param groupInputs 组内所有设备数据（不能为空，至少2个设备）
     * @return 每个设备的空间校验结果（顺序与输入一致）
     */
    List<SpatialCheckResult> spatialCheck(List<SpatialGroupInput> groupInputs);

    /**
     * 服务是否可用
     *
     * @return true=可用
     */
    boolean isAvailable();
}