package org.gnss;

import org.gnss.model.CleanResult;
import org.gnss.model.DisplacementResult;
import org.gnss.model.GnssMetaData;

/**
 * GNSS数据清洗引擎接口
 * <p>接收GNSS定位数据，执行多层递进式质量控制和清洗</p>
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