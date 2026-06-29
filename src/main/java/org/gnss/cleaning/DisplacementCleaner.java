package org.gnss.cleaning;

import org.gnss.cache.DeviceStateCache;
import org.gnss.config.CleanConfig;
import org.gnss.config.Algorithm;
import org.gnss.model.CleanResult;
import org.gnss.model.DeviceDiagnosis;
import org.gnss.model.DeviceState;
import org.gnss.model.DeviceState.DirectionRecord;
import org.gnss.model.DisplacementResult;
import org.gnss.model.SolutionStatus;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 六层递进过滤清洗器
 * <p>
 * 对位移结果进行逐层递进的质量控制，每一层独立判断，未通过则标记异常并阻止进入下一层。
 * 清洗流程：质量门禁 → 跳变检测 → 统计粗差 → 值替换 → 基线记忆 → 空间联合校验。
 * </p>
 *
 * <h3>六层清洗说明</h3>
 * <table>
 *   <tr><th>层级</th><th>名称</th><th>作用</th></tr>
 *   <tr><td>1</td><td>质量门禁</td><td>卫星数、PDOP、RMS、Ratio等基本质量检查</td></tr>
 *   <tr><td>2</td><td>跳变检测</td><td>相邻历元位移突变检测，支持同向趋势快速通道</td></tr>
 *   <tr><td>3</td><td>统计粗差</td><td>滑动窗口Hampel/IQR/3σ粗差检测</td></tr>
 *   <tr><td>4</td><td>值替换</td><td>粗差被替换为窗口中位数/均值</td></tr>
 *   <tr><td>5</td><td>基线记忆</td><td>快慢基线对比，检测阶跃偏移</td></tr>
 *   <tr><td>6</td><td>空间联合校验</td><td>多设备空间一致性校验，离群值替换为组中位数</td></tr>
 * </table>
 */
public class DisplacementCleaner {

    private final CleanConfig config;
    private final DeviceStateCache cache;

    // 动态阈值（由诊断模块反馈调整）
    private double effectiveMaxStepHorizontal;
    private double effectiveMaxStepVertical;
    private double effectiveVerticalToleranceFactor;
    private boolean thresholdsAdjusted;

    /**
     * 构造清洗器
     *
     * @param config 清洗配置（阈值参数）
     * @param cache  设备状态缓存（用于存取滑动窗口、基线等）
     */
    public DisplacementCleaner(CleanConfig config, DeviceStateCache cache) {
        this.config = config;
        this.cache = cache;
        restoreDefaultThresholds();
    }

    /**
     * 应用诊断反馈，动态调整清洗阈值
     * <p>
     * 三种模式：
     * <ul>
     *   <li>温漂模式：水平跳变阈值×1.6，高程跳变阈值×1.5，IQR系数×2.0</li>
     *   <li>周期波动模式：当前时间处于波动时段时启用宽松模式</li>
     *   <li>抖动模式：不修改数据，仅标记——此处不调整阈值</li>
     * </ul>
     * </p>
     *
     * @param diagnosis 诊断反馈信号
     */
    public void applyDiagnosisFeedback(DeviceDiagnosis diagnosis) {
        if (!config.adaptiveThresholdEnabled || diagnosis == null) {
            restoreDefaultThresholds();
            return;
        }

        boolean adjusted = false;

        // 温漂模式：温度和位移相关性显著
        if (diagnosis.isThermalModeActive()
                && Math.abs(diagnosis.getThermalCorrelation()) > config.thermalCorrelationThreshold) {
            effectiveMaxStepHorizontal = config.maxStepHorizontal * config.thermalModeScaleHorizontal;
            effectiveMaxStepVertical = config.maxStepVertical * config.thermalModeScaleVertical;
            effectiveVerticalToleranceFactor = config.verticalToleranceFactor * config.thermalIqrScaleMultiplier;
            adjusted = true;
        }

        // 周期性波动模式：当前处于高波动时段
        if (diagnosis.isInPeriodicFluctuation() && isInPeriodicWindow(diagnosis)) {
            effectiveMaxStepHorizontal = config.maxStepHorizontal * config.thermalModeScaleHorizontal;
            effectiveMaxStepVertical = config.maxStepVertical * config.thermalModeScaleVertical;
            effectiveVerticalToleranceFactor = config.verticalToleranceFactor * config.thermalIqrScaleMultiplier;
            adjusted = true;
        }

        if (!adjusted) {
            restoreDefaultThresholds();
        }
        thresholdsAdjusted = adjusted;
    }

    /**
     * 检查当前时间是否处于周期性波动时段
     */
    private boolean isInPeriodicWindow(DeviceDiagnosis diagnosis) {
        int start = diagnosis.getPeriodicStartMinute();
        int end = diagnosis.getPeriodicEndMinute();
        if (start < 0 || end < 0) {
            return false;
        }
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        int currentMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (start <= end) {
            return currentMinute >= start && currentMinute <= end;
        } else {
            return currentMinute >= start || currentMinute <= end;
        }
    }

    /**
     * 恢复默认阈值
     */
    public void restoreDefaultThresholds() {
        effectiveMaxStepHorizontal = config.maxStepHorizontal;
        effectiveMaxStepVertical = config.maxStepVertical;
        effectiveVerticalToleranceFactor = config.verticalToleranceFactor;
        thresholdsAdjusted = false;
    }

    /**
     * 当前阈值是否已被调整
     */
    public boolean isThresholdsAdjusted() {
        return thresholdsAdjusted;
    }

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
    public CleanResult cleanSingle(DisplacementResult result) {
        CleanResult r1 = layer1QualityGate(result);
        if (!r1.isPassed()) {
            return r1;
        }

        DeviceState tempState = new DeviceState();
        tempState.setLastValidNorth(result.getdNorth());
        tempState.setLastValidEast(result.getdEast());
        tempState.setLastValidUp(result.getdUp());

        CleanResult r3 = layer3StatisticalOutlier(result, tempState);
        if (!r3.isPassed()) {
            layer4AnomalyReplacement(result, tempState, r3);
        }

        layer5BaselineMemory(result, tempState);

        result.setCleaned(true);

        if (result.isAbnormal()) {
            return CleanResult.fail(result, 0, result.getAbnormalReason());
        } else {
            return CleanResult.pass(result);
        }
    }

    /**
     * 带历史上下文的完整六层清洗
     * <p>
     * 执行流程：
     * <ol>
     *   <li>质量门禁：检查卫星数、PDOP、RMS、Ratio</li>
     *   <li>跳变检测：相邻历元位移突变检测</li>
     *   <li>统计粗差：滑动窗口Hampel/IQR/3σ检测</li>
     *   <li>值替换：将粗差替换为窗口中位数/均值（仅当第3层检测到粗差时）</li>
     *   <li>基线记忆：快慢基线对比检测阶跃</li>
     *   <li>空间联合校验：多设备空间一致性校验，离群值替换为组中位数</li>
     * </ol>
     * 全部通过后更新滑动窗口和上一合法值
     * </p>
     *
     * @param result   位移结果
     * @param deviceId 设备ID
     * @return 清洗结果
     */
    public CleanResult cleanWithHistory(DisplacementResult result, String deviceId) {
        DeviceState state = cache.getOrCreate(deviceId);

        if (config.adaptiveThresholdEnabled) {
            DeviceDiagnosis lastDiag = state.getLastDiagnosis();
            applyDiagnosisFeedback(lastDiag);
        }

        double stepFlag = 0.0;
        double timeSeriesResidual = 0.0;
        double spatialResidual = 0.0;
        double horizontalChangeRate = 0.0;
        double verticalChangeRate = 0.0;
        double sameDirectionNeighborRatio = 1.0;
        double windowStability = 0.0;

        CleanResult r1 = layer1QualityGate(result);
        if (!r1.isPassed()) {
            return r1;
        }

        CleanResult r2 = layer2JumpDetection(result, state);
        if (!r2.isPassed()) {
            stepFlag = 1.0;
        }
        horizontalChangeRate = computeHorizontalChangeRate(result, state);
        verticalChangeRate = computeVerticalChangeRate(result, state);

        timeSeriesResidual = computeTimeSeriesResidual(result, state);

        CleanResult r3 = layer3StatisticalOutlier(result, state);
        if (!r3.isPassed()) {
            layer4AnomalyReplacement(result, state, r3);
        }

        CleanResult r5 = layer5BaselineMemory(result, state);
        if (!r5.isPassed()) {
            stepFlag = 1.0;
        }

        spatialResidual = computeSpatialResidual(result, state, deviceId);
        sameDirectionNeighborRatio = computeSameDirectionNeighborRatio(result, state, deviceId);
        if (!result.isAbnormal()) {
            layer6SpatialCheck(result, state, deviceId);
        }

        updateWindows(result, state);
        result.setCleaned(true);

        double solutionQuality = computeSolutionQuality(result);
        windowStability = computeWindowStability(state);

        CleanResult finalResult;
        if (result.isAbnormal()) {
            finalResult = CleanResult.fail(result, 0, result.getAbnormalReason());
        } else {
            finalResult = CleanResult.pass(result);
        }
        finalResult.setTimeSeriesResidual(timeSeriesResidual);
        finalResult.setSpatialResidual(spatialResidual);
        finalResult.setSolutionQuality(solutionQuality);
        finalResult.setStepFlag(stepFlag);
        finalResult.setHorizontalChangeRate(horizontalChangeRate);
        finalResult.setVerticalChangeRate(verticalChangeRate);
        finalResult.setSameDirectionNeighborRatio(sameDirectionNeighborRatio);
        finalResult.setWindowStability(windowStability);
        return finalResult;
    }

    /**
     * 带气象数据的清洗（温度附加）
     * <p>在清洗前将温度数据写入设备状态，用于温漂分析</p>
     *
     * @param result      位移结果
     * @param deviceId    设备ID
     * @param temperature 温度（℃）
     * @return 清洗结果
     */
    public CleanResult cleanWithHistoryAndMeta(DisplacementResult result, String deviceId, double temperature) {
        DeviceState state = cache.getOrCreate(deviceId);
        state.setLastTemperature(temperature);
        return cleanWithHistory(result, deviceId);
    }

    // ==================== 第一层：质量门禁 ====================

    /**
     * 质量门禁（第一层）
     * <p>
     * 对解算结果的基本质量指标进行检查：
     * <ul>
     *   <li>INVALID/SINGLE解直接拒绝</li>
     *   <li>FLOAT解：宽松阈值（卫星数、PDOP、RMS）</li>
     *   <li>FIX解：严格阈值（卫星数、PDOP、RMS、Ratio）</li>
     * </ul>
     * </p>
     *
     * @param result 位移结果
     * @return 清洗结果
     */
    private CleanResult layer1QualityGate(DisplacementResult result) {
        SolutionStatus status = result.getStatus();

        if (status == SolutionStatus.INVALID || status == SolutionStatus.SINGLE) {
            result.setAbnormal(true);
            result.setAbnormalReason("Layer1: invalid/single solution");
            result.setStatus(SolutionStatus.INVALID);
            return CleanResult.fail(result, 1, "Invalid or single solution");
        }

        if (config.maxDisplacement > 0) {
            double absN = Math.abs(result.getdNorth());
            double absE = Math.abs(result.getdEast());
            double absU = Math.abs(result.getdUp());
            if (absN > config.maxDisplacement || absE > config.maxDisplacement || absU > config.maxDisplacement) {
                result.setAbnormal(true);
                result.setAbnormalReason(String.format("Layer1: displacement exceeds max (%.4f/%.4f/%.4f > %.4f)",
                        absN, absE, absU, config.maxDisplacement));
                return CleanResult.fail(result, 1, "Displacement exceeds maximum threshold");
            }
        }

        if (status == SolutionStatus.FLOAT) {
            result.setDowngraded(true);
            if (result.getNumSatellites() < config.minSatellite) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: FLOAT with < " + config.minSatellite + " satellites");
                return CleanResult.fail(result, 1, "FLOAT with insufficient satellites");
            }
            if (result.getPdop() > config.maxPdopFloat) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: FLOAT PDOP > " + config.maxPdopFloat);
                return CleanResult.fail(result, 1, "FLOAT PDOP too high");
            }
            if (result.getRms() > config.maxRmsFloat) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: FLOAT RMS > " + config.maxRmsFloat);
                return CleanResult.fail(result, 1, "FLOAT RMS too high");
            }
        }

        if (status == SolutionStatus.FIX) {
            if (result.getNumSatellites() < config.minSatellite) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: FIX with < " + config.minSatellite + " satellites");
                return CleanResult.fail(result, 1, "FIX with insufficient satellites");
            }
            if (result.getPdop() > config.maxPdop) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: FIX PDOP > " + config.maxPdop);
                return CleanResult.fail(result, 1, "FIX PDOP too high");
            }
            if (result.getRms() > config.maxRms) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: FIX RMS > " + config.maxRms);
                return CleanResult.fail(result, 1, "FIX RMS too high");
            }
            if (result.getRatio() < config.minRatio) {
                result.setAbnormal(true);
                result.setAbnormalReason("Layer1: ratio < " + config.minRatio);
                return CleanResult.fail(result, 1, "Ratio too low");
            }
        }

        return CleanResult.pass(result);
    }

    // ==================== 第二层：相邻跳变检测 ====================

    /**
     * 跳变检测（第二层）
     * <p>
     * 检测相邻历元间位移是否发生突变：
     * <ul>
     *   <li>水平方向跳变 &gt; maxStepHorizontal（默认0.05m）</li>
     *   <li>高程方向跳变 &gt; maxStepVertical（默认0.08m）</li>
     * </ul>
     * 支持同向趋势快速通道：连续N个历元同向跳变判定为真实位移，放行。
     * </p>
     *
     * @param result 当前位移结果
     * @param state  设备状态（含上一合法值）
     * @return 清洗结果
     */
    private CleanResult layer2JumpDetection(DisplacementResult result, DeviceState state) {
        double lastN = state.getLastValidNorth();
        double lastE = state.getLastValidEast();
        double lastU = state.getLastValidUp();

        if (!state.isLastValidInitialized()) {
            return CleanResult.pass(result);
        }

        // 计算当前跳变量
        double dN = Math.abs(result.getdNorth() - lastN);
        double dE = Math.abs(result.getdEast() - lastE);
        double dU = Math.abs(result.getdUp() - lastU);

        boolean jumpDetected = dN > effectiveMaxStepHorizontal
                || dE > effectiveMaxStepHorizontal
                || dU > effectiveMaxStepVertical;

        if (!jumpDetected) {
            // 未触发跳变，重置同向趋势计数（跳变链中断）
            state.resetConsecutiveSameDirection();
            return CleanResult.pass(result);
        }

        // 计算当前跳变方向符号
        double signN = Math.signum(result.getdNorth() - lastN);
        double signE = Math.signum(result.getdEast() - lastE);
        double signU = Math.signum(result.getdUp() - lastU);

        // ========== 同向趋势快速通道 ==========
        // 有历史记忆时：检查连续N个历元是否三维同向 + 时间窗口
        // 无历史记忆时：退化为简单跳变检查（只检查最后一个历元）
        if (config.trendQuickReleaseEnabled) {
            List<DirectionRecord> history = state.getJumpDirectionHistory();

            if (!history.isEmpty()) {
                // ----- 有历史记忆：完整快速通道 -----
                boolean allSameDirection = true;
                int checkCount = Math.min(config.trendQuickReleaseCount - 1, history.size());

                for (int i = 0; i < checkCount; i++) {
                    DirectionRecord rec = history.get(history.size() - 1 - i);
                    if (!rec.isSameDirection(signN, signE, signU)) {
                        allSameDirection = false;
                        break;
                    }
                }

                if (allSameDirection && checkCount >= config.trendQuickReleaseCount - 1) {
                    long firstRecTime = history.get(history.size() - checkCount).getTimestamp();
                    long elapsedSeconds = (System.currentTimeMillis() - firstRecTime) / 1000;

                    if (config.trendQuickReleaseMaxSeconds == 0
                            || elapsedSeconds <= config.trendQuickReleaseMaxSeconds) {
                        // 快速通道放行：确认为真实形变
                        state.recordJumpDirection(signN, signE, signU, config.trendQuickReleaseCount * 2);
                        state.updateConsecutiveSameDirection(true);
                        result.setAbnormal(false);
                        result.setAbnormalReason(null);
                        return CleanResult.pass(result);
                    }
                }
            }
            // else: 无历史记忆，退化为下面的简单跳变检查
        }

        // ========== 未通过快速通道：标记为跳变异常 ==========
        result.setAbnormal(true);
        result.setAbnormalReason("Layer2: jump N=" + String.format("%.4f", dN)
                + " E=" + String.format("%.4f", dE) + " U=" + String.format("%.4f", dU));

        // 记录跳变方向（用于后续快速通道判断）
        state.recordJumpDirection(signN, signE, signU, config.trendQuickReleaseCount * 2);
        state.updateConsecutiveSameDirection(false);

        // 更新上一合法值（即使跳变也更新，避免死循环）
        state.setLastValidNorth(result.getdNorth());
        state.setLastValidEast(result.getdEast());
        state.setLastValidUp(result.getdUp());

        return CleanResult.fail(result, 2, "Jump detected, waiting for confirmation");
    }

    // ==================== 第三层：滑动窗口统计粗差检测 ====================

    /**
     * 统计粗差检测（第三层）
     * <p>
     * 基于滑动窗口（默认20条）进行统计粗差检测：
     * <ul>
     *   <li>IQR法：值超出 [Q1 - 1.5*IQR, Q3 + 1.5*IQR] 视为粗差</li>
     *   <li>3σ法：值偏离均值超过3倍标准差视为粗差</li>
     * </ul>
     * 窗口不足3条时直接放行
     * </p>
     *
     * @param result 当前位移结果
     * @param state  设备状态（含滑动窗口）
     * @return 清洗结果（未通过表示检测到粗差，需进入第4层值替换）
     */
    private CleanResult layer3StatisticalOutlier(DisplacementResult result, DeviceState state) {
        // FLOAT解精度低，跳过统计检测，避免污染统计窗口
        if (result.isDowngraded()) {
            return CleanResult.pass(result);
        }

        LinkedList<Double> nw = state.getNorthWindow();
        LinkedList<Double> ew = state.getEastWindow();
        LinkedList<Double> uw = state.getUpWindow();

        // 窗口未满时的保守启动策略：使用初始基线 + 全局经验阈值判异
        if (nw.size() < config.windowSize) {
            if (state.isInitialBaselineEstablished()) {
                double baselineN = state.getInitialBaselineNorth();
                double baselineE = state.getInitialBaselineEast();
                double baselineU = state.getInitialBaselineUp();

                boolean baselineOutlier = Math.abs(result.getdNorth() - baselineN) > config.windowInitFallbackSigma
                        || Math.abs(result.getdEast() - baselineE) > config.windowInitFallbackSigma
                        || Math.abs(result.getdUp() - baselineU) > config.windowInitFallbackSigma;

                if (baselineOutlier) {
                    return CleanResult.fail(result, 3, "Window uninitialized, outlier vs baseline");
                }
            }
            // 窗口不足3条时直接放行
            if (nw.size() < 3) {
                return CleanResult.pass(result);
            }
        }

        boolean isOutlier = false;

        if (config.algorithm == Algorithm.HAMPEL) {
            isOutlier = checkHampel(result.getdNorth(), nw, config.hampelK)
                    || checkHampel(result.getdEast(), ew, config.hampelK)
                    || checkHampel(result.getdUp(), uw, config.hampelKVertical);
        } else if (config.algorithm == Algorithm.IQR) {
            isOutlier = checkIQR(result.getdNorth(), nw, "N") || checkIQR(result.getdEast(), ew, "E")
                    || checkIQRVertical(result.getdUp(), uw, "U");
        } else {
            isOutlier = checkThreeSigma(result.getdNorth(), nw, "N") || checkThreeSigma(result.getdEast(), ew, "E")
                    || checkThreeSigma(result.getdUp(), uw, "U");
        }

        if (isOutlier) {
            return CleanResult.fail(result, 3, "Statistical outlier");
        }
        return CleanResult.pass(result);
    }

    /**
     * IQR粗差检测（水平方向）
     * <p>值超出 [Q1 - 1.5*IQR, Q3 + 1.5*IQR] 视为粗差</p>
     *
     * @param value     待检测值
     * @param window    滑动窗口数据
     * @param component 分量名称（N/E/U）
     * @return true=粗差
     */
    private boolean checkIQR(double value, LinkedList<Double> window, String component) {
        List<Double> sorted = new java.util.ArrayList<>(window);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        double q1 = sorted.get(n / 4);
        double q3 = sorted.get(3 * n / 4);
        double iqr = q3 - q1;
        double lower = q1 - 1.5 * iqr;
        double upper = q3 + 1.5 * iqr;
        return value < lower || value > upper;
    }

    /**
     * IQR粗差检测（高程方向，容忍系数可调）
     *
     * @param value     待检测值
     * @param window    滑动窗口数据
     * @param component 分量名称
     * @return true=粗差
     */
    private boolean checkIQRVertical(double value, LinkedList<Double> window, String component) {
        List<Double> sorted = new java.util.ArrayList<>(window);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        double q1 = sorted.get(n / 4);
        double q3 = sorted.get(3 * n / 4);
        double iqr = q3 - q1;
        double lower = q1 - effectiveVerticalToleranceFactor * 1.5 * iqr;
        double upper = q3 + effectiveVerticalToleranceFactor * 1.5 * iqr;
        return value < lower || value > upper;
    }

    /**
     * 3σ粗差检测
     * <p>值偏离均值超过3倍标准差视为粗差</p>
     *
     * @param value     待检测值
     * @param window    滑动窗口数据
     * @param component 分量名称
     * @return true=粗差
     */
    private boolean checkThreeSigma(double value, LinkedList<Double> window, String component) {
        double mean = 0;
        for (double v : window) {
            mean += v;
        }
        mean /= window.size();
        double var = 0;
        for (double v : window) {
            var += (v - mean) * (v - mean);
        }
        var /= window.size();
        double sigma = Math.sqrt(var);
        if (sigma < 1e-10) {
            sigma = config.windowInitFallbackSigma;
        }
        return Math.abs(value - mean) > 3 * sigma;
    }

    /**
     * Hampel标识符粗差检测（去趋势残差）
     * <p>
     * 步骤：
     * <ol>
     *   <li>对窗口数据做线性拟合去趋势，得到残差序列</li>
     *   <li>计算残差的中位数和MAD</li>
     *   <li>当前值去趋势后的残差 |ri - median| > k * MAD 视为粗差</li>
     * </ol>
     * 去趋势的目的是消除真实形变趋势对统计检测的干扰，避免将趋势误判为粗差。
     * </p>
     *
     * @param value  待检测值
     * @param window 滑动窗口数据
     * @param k      Hampel阈值系数
     * @return true=粗差
     */
    private boolean checkHampel(double value, LinkedList<Double> window, double k) {
        if (window.size() < 3) {
            return false;
        }

        double[] residuals = detrendedResiduals(window, value);

        double med = median(residuals);
        double madVal = mad(residuals, med);
        if (madVal < 1e-10) {
            madVal = config.windowInitFallbackSigma;
        }

        double currentValueResidual = residuals[residuals.length - 1];
        return Math.abs(currentValueResidual - med) > k * madVal;
    }

    /**
     * 对窗口数据做线性拟合去趋势，返回残差序列（含当前值）
     * <p>
     * 线性拟合 y = a + b*x，残差 = y - (a + b*x)
     * 当前值追加到窗口末尾参与拟合，返回全部残差
     * </p>
     *
     * @param window      滑动窗口数据
     * @param currentValue 当前待检测值
     * @return 残差数组，最后一个元素为当前值的残差
     */
    private double[] detrendedResiduals(LinkedList<Double> window, double currentValue) {
        int n = window.size() + 1;
        double[] residuals = new double[n];

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < window.size(); i++) {
            double x = i;
            double y = window.get(i);
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        {
            double x = window.size();
            double y = currentValue;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denom = n * sumX2 - sumX * sumX;
        double b, a;
        if (Math.abs(denom) < 1e-15) {
            a = sumY / n;
            b = 0;
        } else {
            b = (n * sumXY - sumX * sumY) / denom;
            a = (sumY - b * sumX) / n;
        }

        for (int i = 0; i < window.size(); i++) {
            residuals[i] = window.get(i) - (a + b * i);
        }
        residuals[window.size()] = currentValue - (a + b * window.size());

        return residuals;
    }

    /**
     * 计算数组的MAD（中位数绝对偏差）
     */
    private double mad(double[] values, double median) {
        double[] absDevs = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            absDevs[i] = Math.abs(values[i] - median);
        }
        java.util.Arrays.sort(absDevs);
        int n = absDevs.length;
        if (n % 2 == 0) {
            return (absDevs[n / 2 - 1] + absDevs[n / 2]) / 2.0;
        } else {
            return absDevs[n / 2];
        }
    }

    /**
     * 计算数组的中位数
     */
    private double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 0) {
            return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        } else {
            return sorted[n / 2];
        }
    }

    // ==================== 第四层：异常值替换 ====================

    /**
     * 异常值替换（第四层）
     * <p>
     * 当第3层检测到粗差时，将粗差值替换为：
     * <ul>
     *   <li>窗口满时：窗口中位数（IQR）或均值（3σ）</li>
     *   <li>窗口未满时：上一合法值</li>
     * </ul>
     * 替换后的值可继续参与后续清洗和窗口更新。
     * </p>
     *
     * @param result 当前位移结果（原地修改）
     * @param state  设备状态
     * @param r3     第3层清洗结果
     */
    private void layer4AnomalyReplacement(DisplacementResult result, DeviceState state, CleanResult r3) {
        if (r3.isPassed()) {
            return;
        }

        LinkedList<Double> nw = state.getNorthWindow();
        LinkedList<Double> ew = state.getEastWindow();
        LinkedList<Double> uw = state.getUpWindow();

        if (nw.size() >= config.windowSize) {
            if (config.algorithm == Algorithm.THREE_SIGMA) {
                result.setdNorth(mean(nw));
                result.setdEast(mean(ew));
                result.setdUp(mean(uw));
            } else {
                result.setdNorth(median(nw));
                result.setdEast(median(ew));
                result.setdUp(median(uw));
            }
        } else {
            result.setdNorth(state.getLastValidNorth());
            result.setdEast(state.getLastValidEast());
            result.setdUp(state.getLastValidUp());
        }
        result.setAbnormal(false);
        result.setAbnormalReason("Layer4: replaced outlier");
    }

    // ==================== 第五层：长期基线记忆 ====================

    /**
     * 基线记忆（第五层）
     * <p>
     * 维护快慢两条基线（5分钟 vs 24小时），当检测到阶跃偏差时：
     * <ul>
     *   <li>快慢基线中位数差异 &lt; 3mm → 判定为真实位移，放行</li>
     *   <li>否则不阻止（让后续分析判断）</li>
     * </ul>
     * </p>
     *
     * @param result 当前位移结果
     * @param state  设备状态（含快慢基线）
     * @return 清洗结果
     */
    private CleanResult layer5BaselineMemory(DisplacementResult result, DeviceState state) {
        LinkedList<Double> fbN = state.getFastBaselineNorth();
        LinkedList<Double> fbE = state.getFastBaselineEast();
        LinkedList<Double> fbU = state.getFastBaselineUp();
        LinkedList<Double> sbN = state.getSlowBaselineNorth();
        LinkedList<Double> sbE = state.getSlowBaselineEast();
        LinkedList<Double> sbU = state.getSlowBaselineUp();

        double dN = Math.abs(result.getdNorth() - state.getLastValidNorth());
        double dE = Math.abs(result.getdEast() - state.getLastValidEast());
        double dU = Math.abs(result.getdUp() - state.getLastValidUp());

        boolean largeJump = dN > config.stepDeviationThreshold
                || dE > config.stepDeviationThreshold
                || dU > config.stepDeviationThreshold;

        // ========== 阶段1：检测到大幅跳变，进入观察期 ==========
        if (largeJump && state.getStepObservationCount() == 0) {
            state.setStepCandidateNorth(result.getdNorth());
            state.setStepCandidateEast(result.getdEast());
            state.setStepCandidateUp(result.getdUp());
            state.setStepStartTime(System.currentTimeMillis());
            state.setStepObservationCount(1);
            return CleanResult.fail(result, 5, "Step detected, entering observation");
        }

        // ========== 阶段2：观察期内，累计稳定历元 ==========
        if (state.getStepObservationCount() > 0
                && state.getStepObservationCount() < config.stepObservationCount) {
            // 超时保护：观察期过长则放弃
            long elapsed = System.currentTimeMillis() - state.getStepStartTime();
            if (elapsed > config.stepMaxTimeoutSeconds * 1000L) {
                state.setStepObservationCount(0);
                state.setStepCandidateNorth(0);
                state.setStepCandidateEast(0);
                state.setStepCandidateUp(0);
                state.setStepStartTime(0);
                return CleanResult.fail(result, 5, "Step observation timeout");
            }

            double candidateN = state.getStepCandidateNorth();
            double candidateE = state.getStepCandidateEast();
            double candidateU = state.getStepCandidateUp();

            boolean stable = Math.abs(result.getdNorth() - candidateN) < config.stepStableThreshold
                    && Math.abs(result.getdEast() - candidateE) < config.stepStableThreshold
                    && Math.abs(result.getdUp() - candidateU) < config.stepStableThreshold;

            if (stable) {
                state.setStepObservationCount(state.getStepObservationCount() + 1);
            } else {
                // 不稳定，重置观察状态
                state.setStepObservationCount(0);
                state.setStepCandidateNorth(0);
                state.setStepCandidateEast(0);
                state.setStepCandidateUp(0);
                state.setStepStartTime(0);
            }
            return CleanResult.fail(result, 5,
                    "Observing step (" + state.getStepObservationCount() + "/" + config.stepObservationCount + ")");
        }

        // ========== 阶段3：观察期结束，确认稳定，执行回正 ==========
        if (state.getStepObservationCount() >= config.stepObservationCount) {
            double fbMedN = fbN.size() >= 2 ? median(fbN) : result.getdNorth();
            double fbMedE = fbE.size() >= 2 ? median(fbE) : result.getdEast();
            double fbMedU = fbU.size() >= 2 ? median(fbU) : result.getdUp();
            double sbMedN = sbN.size() >= 2 ? median(sbN) : fbMedN;
            double sbMedE = sbE.size() >= 2 ? median(sbE) : fbMedE;
            double sbMedU = sbU.size() >= 2 ? median(sbU) : fbMedU;

            double diffN = Math.abs(fbMedN - sbMedN);
            double diffE = Math.abs(fbMedE - sbMedE);
            double diffU = Math.abs(fbMedU - sbMedU);
            double maxDiff = Math.max(diffN, Math.max(diffE, diffU));

            if (maxDiff < config.stepConfirmThreshold) {
                // 确认阶跃，回正到滑动窗口中位数（N/E/U三维统一基准）
                result.setdNorth(median(state.getNorthWindow()));
                result.setdEast(median(state.getEastWindow()));
                result.setdUp(median(state.getUpWindow()));
                result.setAbnormal(false);
                result.setAbnormalReason(null);
                // 重置观察状态
                state.setStepObservationCount(0);
                state.setStepCandidateNorth(0);
                state.setStepCandidateEast(0);
                state.setStepCandidateUp(0);
                state.setStepStartTime(0);
                return CleanResult.pass(result);
            } else {
                // 未确认，放弃回正
                state.setStepObservationCount(0);
                return CleanResult.fail(result, 5, "Step not confirmed, maxDiff=" + String.format("%.4f", maxDiff));
            }
        }

        return CleanResult.pass(result);
    }

    // ==================== 第六层：空间联合校验 ====================

    /**
     * 空间联合校验（第六层）
     * <p>
     * 利用多设备空间一致性，检测当前设备是否为空间离群点：
     * <ul>
     *   <li>收集缓存中其他设备的最近合法位移值</li>
     *   <li>计算组中位数作为空间参考</li>
     *   <li>当前设备与组中位数偏差超过阈值时，判定为空间离群</li>
     *   <li>离群时替换为组中位数，清除异常标记</li>
     * </ul>
     * 需要至少 spatialMinNeighbors 个邻居设备才执行校验。
     * </p>
     *
     * @param result   当前位移结果（原地修改）
     * @param state    当前设备状态
     * @param deviceId 当前设备ID
     */
    private void layer6SpatialCheck(DisplacementResult result, DeviceState state, String deviceId) {
        if (!config.enableSpatialCheck) {
            return;
        }

        Map<String, DeviceState> allStates = cache.getAllDeviceStates();

        List<Double> otherN = new ArrayList<>();
        List<Double> otherE = new ArrayList<>();
        List<Double> otherU = new ArrayList<>();

        for (Map.Entry<String, DeviceState> entry : allStates.entrySet()) {
            if (!entry.getKey().equals(deviceId)) {
                DeviceState other = entry.getValue();
                if (other.getLastUpdateTime() > 0) {
                    otherN.add(other.getLastValidNorth());
                    otherE.add(other.getLastValidEast());
                    otherU.add(other.getLastValidUp());
                }
            }
        }

        if (otherN.size() < config.spatialMinNeighbors) {
            return;
        }

        double groupMedianN = median(otherN);
        double groupMedianE = median(otherE);
        double groupMedianU = median(otherU);

        double devN = Math.abs(result.getdNorth() - groupMedianN);
        double devE = Math.abs(result.getdEast() - groupMedianE);
        double devU = Math.abs(result.getdUp() - groupMedianU);

        boolean spatialOutlier = devN > config.spatialOutlierThreshold
                || devE > config.spatialOutlierThreshold
                || devU > config.spatialOutlierThreshold;

        if (spatialOutlier) {
            result.setdNorth(groupMedianN);
            result.setdEast(groupMedianE);
            result.setdUp(groupMedianU);
            result.setAbnormal(false);
            result.setAbnormalReason("Layer6: replaced spatial outlier");
            result.setCleaned(true);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 更新滑动窗口和上一合法值
     * <p>
     * 仅对FIX解（非降级）更新滑动窗口和快速基线。
     * 慢速基线始终更新。
     * </p>
     *
     * @param result 当前位移结果
     * @param state  设备状态
     */
    private void updateWindows(DisplacementResult result, DeviceState state) {
        if (result.getStatus() == SolutionStatus.FIX && !result.isDowngraded()) {
            addToWindow(state.getNorthWindow(), result.getdNorth(), config.windowSize);
            addToWindow(state.getEastWindow(), result.getdEast(), config.windowSize);
            addToWindow(state.getUpWindow(), result.getdUp(), config.windowSize);

            addToWindow(state.getFastBaselineNorth(), result.getdNorth(), config.longTermWindowSize);
            addToWindow(state.getFastBaselineEast(), result.getdEast(), config.longTermWindowSize);
            addToWindow(state.getFastBaselineUp(), result.getdUp(), config.longTermWindowSize);

            // 累积初始基线（前10个FIX解均值）
            if (!state.isInitialBaselineEstablished()) {
                state.accumulateInitialBaseline(result.getdNorth(), result.getdEast(), result.getdUp());
            }
        }

        addToWindow(state.getSlowBaselineNorth(), result.getdNorth(), config.slowBaselineSize);
        addToWindow(state.getSlowBaselineEast(), result.getdEast(), config.slowBaselineSize);
        addToWindow(state.getSlowBaselineUp(), result.getdUp(), config.slowBaselineSize);

        state.setPrevValidNorth(state.getLastValidNorth());
        state.setPrevValidEast(state.getLastValidEast());
        state.setPrevValidUp(state.getLastValidUp());
        state.setLastValidNorth(result.getdNorth());
        state.setLastValidEast(result.getdEast());
        state.setLastValidUp(result.getdUp());
        state.setLastValidInitialized(true);
        state.setLastUpdateTime(System.currentTimeMillis());
    }

    /**
     * 向滑动窗口追加值，超出容量时移除最旧值
     *
     * @param window  滑动窗口
     * @param value   新值
     * @param maxSize 最大容量
     */
    private void addToWindow(LinkedList<Double> window, double value, int maxSize) {
        window.addLast(value);
        while (window.size() > maxSize) {
            window.removeFirst();
        }
    }

    /**
     * 计算列表中位数
     *
     * @param list 数值列表
     * @return 中位数
     */
    private double median(List<Double> list) {
        List<Double> sorted = new java.util.ArrayList<>(list);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * 计算列表均值
     *
     * @param list 数值列表
     * @return 均值
     */
    private double mean(List<Double> list) {
        if (list.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (double v : list) {
            sum += v;
        }
        return sum / list.size();
    }

    private double mad(List<Double> values, double median) {
        List<Double> absDevs = new ArrayList<>();
        for (double v : values) {
            absDevs.add(Math.abs(v - median));
        }
        return median(absDevs);
    }

    double computeTimeSeriesResidual(DisplacementResult result, DeviceState state) {
        LinkedList<Double> nw = state.getNorthWindow();
        LinkedList<Double> ew = state.getEastWindow();
        LinkedList<Double> uw = state.getUpWindow();

        if (nw.size() < 3) return 0.0;

        if (config.algorithm == Algorithm.HAMPEL) {
            double[] resN = detrendedResiduals(nw, result.getdNorth());
            double[] resE = detrendedResiduals(ew, result.getdEast());
            double[] resU = detrendedResiduals(uw, result.getdUp());

            double medN = median(resN);
            double medE = median(resE);
            double medU = median(resU);

            double madN = Math.max(mad(resN, medN), 1e-10);
            double madE = Math.max(mad(resE, medE), 1e-10);
            double madU = Math.max(mad(resU, medU), 1e-10);

            double normN = Math.abs(resN[resN.length - 1] - medN) / madN;
            double normE = Math.abs(resE[resE.length - 1] - medE) / madE;
            double normU = Math.abs(resU[resU.length - 1] - medU) / madU;

            return Math.max(normN, Math.max(normE, normU));
        }

        double medN = median(nw);
        double medE = median(ew);
        double medU = median(uw);

        double madN = Math.max(mad(nw, medN), 1e-10);
        double madE = Math.max(mad(ew, medE), 1e-10);
        double madU = Math.max(mad(uw, medU), 1e-10);

        double resN = Math.abs(result.getdNorth() - medN) / madN;
        double resE = Math.abs(result.getdEast() - medE) / madE;
        double resU = Math.abs(result.getdUp() - medU) / madU;

        return Math.max(resN, Math.max(resE, resU));
    }

    double computeSpatialResidual(DisplacementResult result, DeviceState state, String deviceId) {
        if (!config.enableSpatialCheck) {
            return 0.0;
        }

        Map<String, DeviceState> allStates = cache.getAllDeviceStates();

        List<Double> otherN = new ArrayList<>();
        List<Double> otherE = new ArrayList<>();
        List<Double> otherU = new ArrayList<>();

        for (Map.Entry<String, DeviceState> entry : allStates.entrySet()) {
            if (!entry.getKey().equals(deviceId)) {
                DeviceState other = entry.getValue();
                if (other.getLastUpdateTime() > 0) {
                    otherN.add(other.getLastValidNorth());
                    otherE.add(other.getLastValidEast());
                    otherU.add(other.getLastValidUp());
                }
            }
        }

        if (otherN.size() < config.spatialMinNeighbors) {
            return 0.0;
        }

        double groupMedianN = median(otherN);
        double groupMedianE = median(otherE);
        double groupMedianU = median(otherU);

        double devN = Math.abs(result.getdNorth() - groupMedianN);
        double devE = Math.abs(result.getdEast() - groupMedianE);
        double devU = Math.abs(result.getdUp() - groupMedianU);

        return Math.max(devN, Math.max(devE, devU));
    }

    double computeSolutionQuality(DisplacementResult result) {
        if (result.getStatus() == SolutionStatus.INVALID || result.getStatus() == SolutionStatus.SINGLE) {
            return 0.0;
        }

        boolean isFix = result.getStatus() == SolutionStatus.FIX;

        double ratioNorm = isFix ? Math.min(result.getRatio() / 10.0, 1.0) : 0.5;
        double rmsNorm = Math.max(1.0 - result.getRms() / 0.1, 0.0);
        double pdopNorm = Math.max(1.0 - result.getPdop() / 6.0, 0.0);
        double satNorm = Math.min((double) result.getNumSatellites() / 15.0, 1.0);

        double quality = (ratioNorm + rmsNorm + pdopNorm + satNorm) / 4.0;
        if (!isFix) {
            quality *= 0.7;
        }
        return Math.min(quality, 1.0);
    }

    double computeHorizontalChangeRate(DisplacementResult result, DeviceState state) {
        double lastN = state.getLastValidNorth();
        double lastE = state.getLastValidEast();
        double dN = result.getdNorth() - lastN;
        double dE = result.getdEast() - lastE;
        return Math.sqrt(dN * dN + dE * dE);
    }

    double computeVerticalChangeRate(DisplacementResult result, DeviceState state) {
        return Math.abs(result.getdUp() - state.getLastValidUp());
    }

    double computeSameDirectionNeighborRatio(DisplacementResult result, DeviceState state, String deviceId) {
        if (!config.enableSpatialCheck) {
            return 1.0;
        }

        Map<String, DeviceState> allStates = cache.getAllDeviceStates();
        int total = 0;
        int sameDir = 0;

        double dN = result.getdNorth() - state.getLastValidNorth();
        double dE = result.getdEast() - state.getLastValidEast();

        for (Map.Entry<String, DeviceState> entry : allStates.entrySet()) {
            if (entry.getKey().equals(deviceId)) continue;
            DeviceState other = entry.getValue();
            if (other.getLastUpdateTime() <= 0) continue;

            total++;
            double otherDn = other.getLastValidNorth() - other.getPrevValidNorth();
            double otherDe = other.getLastValidEast() - other.getPrevValidEast();

            if (dN * otherDn + dE * otherDe > 0) {
                sameDir++;
            }
        }

        if (total == 0) return 1.0;
        return (double) sameDir / total;
    }

    double computeWindowStability(DeviceState state) {
        LinkedList<Double> nw = state.getNorthWindow();
        LinkedList<Double> ew = state.getEastWindow();
        LinkedList<Double> uw = state.getUpWindow();

        if (nw.isEmpty()) return 0.0;

        double maxN = nw.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double minN = nw.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxE = ew.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double minE = ew.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxU = uw.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double minU = uw.stream().mapToDouble(Double::doubleValue).min().orElse(0);

        double rangeN = maxN - minN;
        double rangeE = maxE - minE;
        double rangeU = maxU - minU;

        return Math.max(rangeN, Math.max(rangeE, rangeU));
    }
}