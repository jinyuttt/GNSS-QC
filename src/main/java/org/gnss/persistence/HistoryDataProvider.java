package org.gnss.persistence;

import org.gnss.model.CleanResult;
import org.gnss.model.Layer7HistoryRecord;

import java.util.List;

/**
 * 第七层历史数据提供者接口
 * <p>由调用方实现，对接 Redis ZSET / 时序库（TDengine/InfluxDB）等外部存储</p>
 * <p>引擎不内置任何存储实现，通过此接口完全解耦</p>
 *
 * <h3>存储选型建议</h3>
 * <table>
 *   <tr><th>存储</th><th>用途</th><th>保留策略</th></tr>
 *   <tr><td>Redis ZSET</td><td>最近200条历史数据，供实时查询</td><td>自动淘汰最老数据</td></tr>
 *   <tr><td>时序库</td><td>全量历史数据，供训练和回溯</td><td>永久保留</td></tr>
 * </table>
 */
public interface HistoryDataProvider {

    /**
     * 写入历史记录（第6层输出后立即调用）
     *
     * @param stationId 测点ID
     * @param timestamp 历元时间戳（毫秒）
     * @param result    第6层输出的清洗结果
     * @param wcsScore  当前历元的加权综合异常分
     */
    void saveHistory(String stationId, long timestamp, CleanResult result, double wcsScore);

    /**
     * 查询最近N条历史记录（第7层判决时调用）
     * <p>返回结果按时间正序排列（最旧在前）</p>
     *
     * @param stationId 测点ID
     * @param limit     查询条数
     * @return 最近N条历史记录，无数据时返回空列表
     */
    List<Layer7HistoryRecord> queryLatest(String stationId, int limit);

    /**
     * 检查外部存储是否可用
     * <p>不可用时第7层自动跳过，直接放行</p>
     *
     * @return true=可用
     */
    boolean isAvailable();

    default List<String> getActiveStationIds() {
        return java.util.List.of();
    }

    default List<org.gnss.model.DisplacementResult> queryRecent(String stationId, int windowSize) {
        return java.util.List.of();
    }
}