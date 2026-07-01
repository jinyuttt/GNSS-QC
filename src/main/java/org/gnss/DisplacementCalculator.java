package org.gnss;

import org.gnss.model.CleanResult;
import org.gnss.model.DisplacementResult;
import org.gnss.model.GnssMetaData;
import org.gnss.model.Layer7ArbitrationResult;
import org.gnss.model.SpatialCheckResult;
import org.gnss.model.SpatialGroupInput;

import java.util.List;

/**
 * GNSS数据清洗引擎接口
 * <p>接收GNSS定位数据，执行多层递进式质量控制和清洗</p>
 *
 * <h3>独立接口</h3>
 * <p>
 * 第6层（空间校验）和第7层（综合仲裁）提供独立调用入口，
 * 可脱离主链路单独使用，适用于：
 * <ul>
 *   <li>离线回溯分析</li>
 *   <li>仅对特定层做调优/验证</li>
 *   <li>与其他系统集成时按需调用</li>
 * </ul>
 * </p>
 */
public interface DisplacementCalculator {

    /**
     * 单条结果清洗（无历史上下文）
     * <p>仅执行第一层质量门禁，适用于无历史数据的场景</p>
     *
     * @param result GNSS位移结果
     * @return 清洗结果
     */
    CleanResult cleanSingle(DisplacementResult result);

    /**
     * 带历史上下文的完整清洗
     * <p>执行完整的多层清洗流水线，结合设备历史状态进行递进过滤</p>
     *
     * @param result   GNSS位移结果
     * @param deviceId 设备ID
     * @return 清洗结果
     */
    CleanResult cleanWithHistory(DisplacementResult result, String deviceId);

    /**
     * 带气象元数据的清洗
     * <p>在清洗前先处理温度数据（温漂分离），然后执行完整清洗流程</p>
     *
     * @param result   GNSS位移结果
     * @param deviceId 设备ID
     * @param meta     气象元数据（温度、气压、湿度）
     * @return 清洗结果
     */
    CleanResult cleanWithHistoryAndMeta(DisplacementResult result, String deviceId, GnssMetaData meta);

    /**
     * 第6层空间一致性校验 — 独立接口
     * <p>
     * 将整个组的设备数据一起传入，对每个设备计算空间残差（F7）、
     * 同向邻居占比（F8），偏差超过阈值的标记为空间离群并返回组中位数替换值。
     * 需要至少 minNeighbors 个邻居才执行校验。
     * </p>
     * <p>
     * 此接口可脱离主链路独立调用，适用于离线空间校验、
     * 回溯分析、与其他系统集成等场景。
     * </p>
     *
     * @param groupInputs 组内所有设备数据（至少2个设备）
     * @return 每个设备的空间校验结果（顺序与输入一致）
     */
    List<SpatialCheckResult> spatialCheck(List<SpatialGroupInput> groupInputs);

    /**
     * 第7层综合仲裁 — 独立接口
     * <p>
     * 对第六层（或第五层）输出的位移结果执行加权综合异常分（WCS）计算、
     * 基于历史的三项判决和趋势保护。
     * </p>
     * <p>
     * 此接口可脱离主链路独立调用，适用于离线仲裁验证、
     * WCS评分计算、与其他系统集成等场景。
     * 第七层未启用时返回 null。
     * </p>
     *
     * @param result              位移结果
     * @param cleanResult         清洗结果
     * @param timeSeriesResidual  第3层时序残差
     * @param spatialResidual     第6层空间残差
     * @param solutionQuality     解算质量归一化值（0~1，1=最优）
     * @param stepFlag            阶跃标记（0或1）
     * @param stationId           测点ID
     * @return 第七层仲裁结果（第七层未启用时返回null）
     */
    Layer7ArbitrationResult layer7Arbitrate(DisplacementResult result,
                                             CleanResult cleanResult,
                                             double timeSeriesResidual,
                                             double spatialResidual,
                                             double solutionQuality,
                                             double stepFlag,
                                             String stationId);

    /**
     * 清除指定设备的状态缓存
     *
     * @param deviceId 设备ID
     */
    void clearDeviceState(String deviceId);

    /**
     * 清除所有设备状态
     */
    void clearAllDeviceState();

    /**
     * 获取当前缓存设备数
     *
     * @return 设备数
     */
    int getDeviceCount();

    /**
     * 动态设置最大缓存设备数
     *
     * @param max 最大设备数
     */
    void setMaxDevices(int max);

    /**
     * 重置所有状态
     */
    void reset();

    /**
     * 关闭引擎，释放资源（如Layer7仲裁器的超时守护线程）
     */
    void shutdown();
}