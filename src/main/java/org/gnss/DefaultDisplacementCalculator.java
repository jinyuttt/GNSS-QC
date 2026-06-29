package org.gnss;

import org.gnss.cache.DeviceStateCache;
import org.gnss.cleaning.DisplacementCleaner;
import org.gnss.cleaning.Layer7Arbitrator;
import org.gnss.config.CacheConfig;
import org.gnss.config.CleanConfig;
import org.gnss.config.DiagnosisConfig;
import org.gnss.config.Layer7Config;
import org.gnss.config.PersistenceConfig;
import org.gnss.diagnosis.DeviceDiagnostician;
import org.gnss.model.*;

import org.gnss.persistence.HistoryDataProvider;
import org.gnss.persistence.PersistenceCallback;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GNSS数据清洗引擎默认实现
 * <p>
 * 串联多层清洗流水线、设备诊断和缓存管理。
 * 核心流程：GNSS定位数据输入 → 多层清洗 → 诊断 → 输出。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CleanConfig cleanConfig = new CleanConfig();
 * DiagnosisConfig diagnosisConfig = new DiagnosisConfig();
 * CacheConfig cacheConfig = new CacheConfig();
 *
 * DefaultDisplacementCalculator calc = new DefaultDisplacementCalculator(
 *     cleanConfig, diagnosisConfig, cacheConfig);
 *
 * DisplacementResult result = new DisplacementResult();
 * result.setdNorth(0.012);
 * result.setdEast(0.005);
 * result.setdUp(0.003);
 * result.setStatus(SolutionStatus.FIX);
 * result.setRatio(5.0);
 * result.setRms(0.02);
 * result.setPdop(2.0);
 * result.setNumSatellites(10);
 *
 * CleanResult cleanResult = calc.cleanWithHistory(result, "device-001");
 * }</pre>
 */
public class DefaultDisplacementCalculator implements DisplacementCalculator {

    private final CleanConfig cleanConfig;
    private final DiagnosisConfig diagnosisConfig;
    private final CacheConfig cacheConfig;
    private final PersistenceConfig persistenceConfig;
    private final PersistenceCallback persistenceCallback;
    private final Layer7Config layer7Config;
    private final HistoryDataProvider historyDataProvider;

    private final DeviceStateCache cache;
    private final DisplacementCleaner cleaner;
    private final DeviceDiagnostician diagnostician;
    private final Layer7Arbitrator layer7Arbitrator;

    private final AtomicInteger idSequence = new AtomicInteger(0);
    private final DateTimeFormatter idFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);

    /**
     * 构造清洗引擎（不含持久化回调）
     */
    public DefaultDisplacementCalculator(CleanConfig cleanConfig,
                                          DiagnosisConfig diagnosisConfig, CacheConfig cacheConfig) {
        this(cleanConfig, diagnosisConfig, cacheConfig, new PersistenceConfig(), null, null, null);
    }

    /**
     * 构造清洗引擎（含持久化回调）
     *
     * @param cleanConfig        清洗配置
     * @param diagnosisConfig    设备诊断配置
     * @param cacheConfig        缓存配置
     * @param persistenceConfig  持久化配置
     * @param persistenceCallback 持久化回调接口实现
     */
    public DefaultDisplacementCalculator(CleanConfig cleanConfig,
                                          DiagnosisConfig diagnosisConfig, CacheConfig cacheConfig,
                                          PersistenceConfig persistenceConfig, PersistenceCallback persistenceCallback) {
        this(cleanConfig, diagnosisConfig, cacheConfig, persistenceConfig, persistenceCallback, null, null);
    }

    /**
     * 构造清洗引擎（含持久化回调和第七层仲裁）
     *
     * @param cleanConfig         清洗配置
     * @param diagnosisConfig     设备诊断配置
     * @param cacheConfig         缓存配置
     * @param persistenceConfig   持久化配置
     * @param persistenceCallback 持久化回调接口实现
     * @param layer7Config        第七层仲裁配置（null则不启用第七层）
     * @param historyDataProvider 第七层历史数据提供者（null则不启用第七层）
     */
    public DefaultDisplacementCalculator(CleanConfig cleanConfig,
                                          DiagnosisConfig diagnosisConfig, CacheConfig cacheConfig,
                                          PersistenceConfig persistenceConfig, PersistenceCallback persistenceCallback,
                                          Layer7Config layer7Config, HistoryDataProvider historyDataProvider) {
        this.cleanConfig = cleanConfig;
        this.diagnosisConfig = diagnosisConfig;
        this.cacheConfig = cacheConfig;
        this.persistenceConfig = persistenceConfig;
        this.persistenceCallback = persistenceCallback;
        this.historyDataProvider = historyDataProvider;

        this.cache = new DeviceStateCache(cacheConfig, persistenceConfig, persistenceCallback);
        this.cleaner = new DisplacementCleaner(cleanConfig, cache);
        this.diagnostician = new DeviceDiagnostician(diagnosisConfig);

        if (historyDataProvider != null) {
            this.layer7Config = (layer7Config != null) ? layer7Config : new Layer7Config();
            this.layer7Arbitrator = new Layer7Arbitrator(this.layer7Config, historyDataProvider);
        } else {
            this.layer7Config = layer7Config;
            this.layer7Arbitrator = null;
        }
    }

    // ==================== 清洗接口 ====================

    /**
     * 单条结果清洗（无历史上下文）
     * <p>
     * 执行流程：Layer1(质量门禁) → Layer3(统计粗差) → Layer4(值替换) → Layer5(基线记忆)
     * <br>跳过Layer2(跳变检测)和Layer6(空间校验)，因为它们依赖历史上下文或多设备数据。
     * <br>使用临时DeviceState，不持久化到缓存。
     * </p>
     *
     * @param result 位移结果
     * @return 清洗结果
     */
    @Override
    public CleanResult cleanSingle(DisplacementResult result) {
        return cleaner.cleanSingle(result);
    }

    /**
     * 带历史上下文的清洗（多层全流程）
     * <p>
     * 执行完整的多层清洗流水线，并在清洗通过后：
     * <ol>
     *   <li>执行设备诊断</li>
     *   <li>添加到降采样历史</li>
     *   <li>记录异常事件</li>
     *   <li>触发持久化回调</li>
     * </ol>
     * </p>
     *
     * @param result   位移结果
     * @param deviceId 设备ID
     * @return 清洗结果
     */
    @Override
    public CleanResult cleanWithHistory(DisplacementResult result, String deviceId) {
        if (persistenceConfig.enablePersistence && persistenceCallback != null) {
            if (!cache.contains(deviceId) && persistenceConfig.recoveryOnStartup) {
                recoverDeviceState(deviceId);
            }
        }

        fillDataIdIfAbsent(result);

        CleanResult cleanResult = cleaner.cleanWithHistory(result, deviceId);

        if (cleanResult.isPassed()) {
            DeviceState state = cache.get(deviceId);
            if (state != null) {
                DeviceDiagnosis diagnosis = diagnostician.diagnose(state, result);
                result.setDiagnosis(diagnosis);

                state.setLastDiagnosis(diagnosis);

                diagnostician.addToDownsampledHistory(result, state);

                if (result.isAbnormal()) {
                    diagnostician.addAnomalyEvent(deviceId, result.getAbnormalReason(), state);
                    if (persistenceConfig.enablePersistence && persistenceCallback != null) {
                        AnomalyEvent event = new AnomalyEvent();
                        event.setDeviceId(deviceId);
                        event.setDataId(result.getDataId());
                        event.setEventTime(result.getTimestamp());
                        event.setDescription(result.getAbnormalReason());
                        event.setEventType("ABNORMAL");
                        persistenceCallback.saveAnomalyEvent(deviceId, event);
                    }
                }
            }
        }

        if (layer7Arbitrator != null && cleanResult.isPassed()) {
            double wcsScore = layer7Arbitrator.computeWcs(
                    cleanResult.getTimeSeriesResidual(),
                    cleanResult.getSpatialResidual(),
                    cleanResult.getSolutionQuality(),
                    cleanResult.getStepFlag());
            cleanResult.setLayer7WcsScore(wcsScore);
            cleanResult.setLayer7ConfidenceScore(1.0 - wcsScore);

            long ts = result.getTimestamp() != null ? result.getTimestamp().toEpochMilli() : System.currentTimeMillis();
            historyDataProvider.saveHistory(deviceId, ts, cleanResult, wcsScore);

            if (layer7Config.enabled) {
                Layer7ArbitrationResult arbResult = layer7Arbitrator.arbitrate(
                        result, cleanResult,
                        cleanResult.getTimeSeriesResidual(),
                        cleanResult.getSpatialResidual(),
                        cleanResult.getSolutionQuality(),
                        cleanResult.getStepFlag(),
                        deviceId
                );

                cleanResult.setLayer7WcsScore(arbResult.getWcsScore());
                cleanResult.setLayer7Corrected(arbResult.isLayer7Corrected());
                cleanResult.setLayer7ConfidenceScore(arbResult.getConfidenceScore());
                cleanResult.setLayer7Skipped(arbResult.isLayer7Skipped());
                cleanResult.setLayer7SkipReason(arbResult.getSkipReason());
                cleanResult.setLayer7TrendProtectionTriggered(arbResult.isTrendProtectionTriggered());

                if (arbResult.isLayer7Corrected()) {
                    result.setAbnormal(true);
                    result.setAbnormalReason("Layer7仲裁替换: " + arbResult.getReplacementSource());
                    result.setCleaned(true);
                }
            }
        }

        if (persistenceConfig.enablePersistence && persistenceCallback != null) {
            persistenceCallback.saveResult(deviceId, result);
        }

        return cleanResult;
    }

    /**
     * 带气象元数据的清洗
     * <p>在清洗前先处理温度数据（温漂分离），然后执行完整清洗流程</p>
     *
     * @param result   位移结果
     * @param deviceId 设备ID
     * @param meta     气象元数据（温度、气压、湿度）
     * @return 清洗结果
     */
    @Override
    public CleanResult cleanWithHistoryAndMeta(DisplacementResult result, String deviceId, GnssMetaData meta) {
        if (meta != null && meta.getTemperature() > -900) {
            DeviceState state = cache.getOrCreate(deviceId);
            diagnostician.processTemperature(meta.getTemperature(), state);
        }
        return cleanWithHistory(result, deviceId);
    }

    // ==================== 设备状态管理 ====================

    /**
     * 清除指定设备的状态缓存
     * <p>如果启用了持久化，会先保存当前状态再清除</p>
     *
     * @param deviceId 设备ID
     */
    @Override
    public void clearDeviceState(String deviceId) {
        if (persistenceConfig.enablePersistence && persistenceCallback != null) {
            DeviceState state = cache.get(deviceId);
            if (state != null) {
                persistenceCallback.saveDeviceState(deviceId, state.toSnapshot(deviceId));
            }
        }
        cache.remove(deviceId);
    }

    /**
     * 清除所有设备状态
     */
    @Override
    public void clearAllDeviceState() {
        cache.clear();
    }

    /**
     * 获取当前缓存设备数
     *
     * @return 设备数
     */
    @Override
    public int getDeviceCount() {
        return cache.size();
    }

    /**
     * 动态设置最大缓存设备数
     *
     * @param max 最大设备数
     */
    @Override
    public void setMaxDevices(int max) {
        cacheConfig.maxDevices = max;
    }

    /**
     * 重置所有状态
     */
    @Override
    public void reset() {
        cache.clear();
    }

    /**
     * 关闭引擎，释放资源（如Layer7仲裁器的超时守护线程）
     */
    @Override
    public void shutdown() {
        if (layer7Arbitrator != null) {
            layer7Arbitrator.shutdown();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 生成唯一数据ID
     * <p>格式：yyyyMMddHHmmssSSS_XXXX（UTC毫秒 + 4位自增序列号）</p>
     * <p>线程安全，同毫秒内自增序列号保证唯一性</p>
     */
    private String generateDataId() {
        String timePart = idFormatter.format(Instant.now());
        String seqPart = String.format("%04X", idSequence.incrementAndGet() & 0xFFFF);
        return timePart + "_" + seqPart;
    }

    /**
     * 如果位移结果缺少dataId，自动生成一个
     */
    private void fillDataIdIfAbsent(DisplacementResult result) {
        if (result.getDataId() == null || result.getDataId().isEmpty()) {
            result.setDataId(generateDataId());
        }
        if (result.getTimestamp() == null) {
            result.setTimestamp(Instant.now());
        }
    }

    /**
     * 从持久化存储恢复设备状态
     */
    private void recoverDeviceState(String deviceId) {
        if (persistenceCallback == null) {
            return;
        }
        try {
            DeviceStateSnapshot snapshot = persistenceCallback.loadDeviceState(deviceId);
            if (snapshot != null) {
                DeviceState state = new DeviceState();
                state.setLastValidNorth(snapshot.getLastValidNorth());
                state.setLastValidEast(snapshot.getLastValidEast());
                state.setLastValidUp(snapshot.getLastValidUp());
                state.setLastTemperature(snapshot.getLastTemperature());
                state.setDenseRecordActive(snapshot.isDenseMode());
                state.setStepObservationCount(snapshot.getStepObservationCount());
                state.setStepCandidateNorth(snapshot.getStepCandidateNorth());
                state.setStepCandidateEast(snapshot.getStepCandidateEast());
                state.setStepCandidateUp(snapshot.getStepCandidateUp());
                state.setStepStartTime(snapshot.getStepStartTime());
                state.setLastValidInitialized(true);
                cache.put(deviceId, state);
            }
        } catch (Exception e) {
            System.err.println("Failed to recover device state for " + deviceId + ": " + e.getMessage());
        }
    }
}