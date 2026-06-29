package org.gnss.persistence;

import org.gnss.model.AnomalyEvent;
import org.gnss.model.DataCorrectionRecord;
import org.gnss.model.DeviceDiagnosis;
import org.gnss.model.DeviceStateSnapshot;
import org.gnss.model.DisplacementResult;

import java.time.Instant;
import java.util.List;

/**
 * 历史数据持久化回调接口
 * <p>由调用方实现，对接 Redis/MySQL/InfluxDB/文件 等任意存储</p>
 * <p>引擎不内置任何存储实现，通过此接口完全解耦</p>
 */
public interface PersistenceCallback {

    // ==================== 清洗结果持久化 ====================

    /**
     * 存储单条清洗后结果
     * <p>包含原始值(original)、清洗值(cleaned)、dataId、状态标记</p>
     *
     * @param deviceId 设备ID
     * @param result   清洗后的位移结果
     */
    void saveResult(String deviceId, DisplacementResult result);

    /**
     * 查询设备最近N条结果
     * <p>用于恢复滑动窗口</p>
     *
     * @param deviceId 设备ID
     * @param count    查询条数
     * @return 最近N条位移结果（按时间倒序）
     */
    List<DisplacementResult> queryRecentResults(String deviceId, int count);

    /**
     * 查询设备指定时间范围的数据
     *
     * @param deviceId 设备ID
     * @param start    起始时间（含）
     * @param end      结束时间（含）
     * @return 时间范围内的位移结果
     */
    List<DisplacementResult> queryTimeRange(String deviceId, Instant start, Instant end);

    // ==================== 设备状态持久化 ====================

    /**
     * 存储设备状态快照
     * <p>LRU淘汰时调用，保存：滑动窗口、上一合法值、双基线、温度状态</p>
     *
     * @param deviceId 设备ID
     * @param snapshot 设备状态快照
     */
    void saveDeviceState(String deviceId, DeviceStateSnapshot snapshot);

    /**
     * 加载设备状态快照
     * <p>设备重新活跃时调用，恢复内存状态</p>
     *
     * @param deviceId 设备ID
     * @return 设备状态快照，无历史数据时返回null
     */
    DeviceStateSnapshot loadDeviceState(String deviceId);

    /**
     * 检查设备是否有历史数据
     *
     * @param deviceId 设备ID
     * @return true=有历史数据可恢复
     */
    boolean hasHistory(String deviceId);

    // ==================== 异常事件持久化 ====================

    /**
     * 存储异常事件
     *
     * @param deviceId 设备ID
     * @param event    异常事件
     */
    void saveAnomalyEvent(String deviceId, AnomalyEvent event);

    /**
     * 查询设备最近的异常事件
     *
     * @param deviceId 设备ID
     * @param count    查询条数
     * @return 最近N条异常事件
     */
    List<AnomalyEvent> queryRecentAnomalies(String deviceId, int count);

    // ==================== 诊断报告持久化 ====================

    /**
     * 存储诊断报告
     *
     * @param deviceId  设备ID
     * @param diagnosis 诊断报告
     */
    void saveDiagnosis(String deviceId, DeviceDiagnosis diagnosis);

    /**
     * 查询设备最新的诊断报告
     *
     * @param deviceId 设备ID
     * @return 最新诊断报告，无数据时返回null
     */
    DeviceDiagnosis queryLatestDiagnosis(String deviceId);

    // ==================== 修正记录持久化 ====================

    /**
     * 存储修正记录
     * <p>诊断模块发现前期误判时调用</p>
     *
     * @param deviceId 设备ID
     * @param record   修正记录
     */
    void saveCorrectionRecord(String deviceId, DataCorrectionRecord record);

    /**
     * 查询设备的所有修正记录
     *
     * @param deviceId 设备ID
     * @return 修正记录列表
     */
    List<DataCorrectionRecord> queryCorrectionRecords(String deviceId);

    /**
     * 查询修正后的数据
     * <p>对指定dataId应用所有修正记录，返回最终值</p>
     *
     * @param deviceId 设备ID
     * @param dataId   数据ID
     * @return 修正后的位移结果，无修正记录时返回原始值
     */
    DisplacementResult queryCorrectedData(String deviceId, String dataId);

    /**
     * 删除设备所有历史数据
     *
     * @param deviceId 设备ID
     */
    void deleteDeviceHistory(String deviceId);
}