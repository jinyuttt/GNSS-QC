package org.gnss.diagnosis;

import org.gnss.config.DiagnosisConfig;
import org.gnss.model.*;

import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;

public class DeviceDiagnostician {

    private final DiagnosisConfig config;
    private double emaTemperature = -999.0;
    private int tempHysteresisCount = 0;
    private long lastDenseRecordTime = 0;

    // 日周期温度剖面
    private double[] tempDailyProfile;       // 每槽的EMA温度
    private int[] tempDailyCount;            // 每槽累计样本数
    private int tempProfileTotalSamples;     // 剖面总样本数
    private boolean tempProfileMature;       // 剖面是否已成熟

    // 温度-位移相关性追踪
    private final LinkedList<Double> tempHistory = new LinkedList<>();   // 温度历史（用于相关性计算）
    private static final int MAX_TEMP_HISTORY = 1440;                    // 最多保留24小时（1条/分钟）
    private long lastFeedbackTime;                                        // 上次反馈信号生成时间
    private boolean thermalModePreviouslyActive;                          // 温漂模式先前状态（用于冷却检测）

    public DeviceDiagnostician(DiagnosisConfig config) {
        this.config = config;
        this.tempDailyProfile = new double[config.tempProfileSlots];
        this.tempDailyCount = new int[config.tempProfileSlots];
        Arrays.fill(tempDailyProfile, config.tempDefaultValue);
    }

    /**
     * 设备诊断（含反馈信号生成）
     * <p>
     * 除常规的抖动/趋势/周期性异常检测外，还计算温度-位移相关系数，
     * 生成温漂模式、周期性波动时段等反馈信号，供清洗模块动态调整阈值。
     * </p>
     *
     * @param state         设备状态（含历史位移和温度）
     * @param currentResult 当前位移结果
     * @return 设备诊断信息（含反馈信号）
     */
    public DeviceDiagnosis diagnose(DeviceState state, DisplacementResult currentResult) {
        DeviceDiagnosis diagnosis = new DeviceDiagnosis();
        diagnosis.setDeviceType(config.deviceType);

        checkJittering(state, diagnosis);
        checkTrend(state, diagnosis);
        checkPeriodicAnomaly(state, diagnosis);

        // 计算温度-位移相关性
        computeThermalCorrelation(state, diagnosis);

        // 识别周期性波动时段
        detectPeriodicFluctuationWindow(state, diagnosis);

        // 生成反馈信号
        generateFeedbackSignal(diagnosis);

        updateHealth(diagnosis);
        return diagnosis;
    }

    /**
     * 计算温度-位移Pearson相关系数
     * <p>使用温度历史和位移降采样历史（North分量），对齐时间窗口计算相关系数</p>
     */
    private void computeThermalCorrelation(DeviceState state, DeviceDiagnosis diagnosis) {
        List<DisplacementResult> history = state.getDownsampledHistory();
        if (tempHistory.size() < 10 || history.size() < 10) {
            diagnosis.setThermalCorrelation(0);
            return;
        }

        // 取两者较小的窗口
        int n = Math.min(tempHistory.size(), history.size());
        // 取最近的n个样本
        double[] temps = new double[n];
        double[] norths = new double[n];
        int tempStart = tempHistory.size() - n;
        int histStart = history.size() - n;
        for (int i = 0; i < n; i++) {
            temps[i] = tempHistory.get(tempStart + i);
            norths[i] = history.get(histStart + i).getdNorth();
        }

        double corr = pearsonCorrelation(temps, norths);
        diagnosis.setThermalCorrelation(corr);

        // 相关系数显著时激活温漂模式
        if (Math.abs(corr) > 0.7) {
            diagnosis.setThermalModeActive(true);
            diagnosis.setThermalModeActivatedTime(System.currentTimeMillis());
        }
    }

    /**
     * 识别周期性高波动时段
     * <p>当检测到24小时周期性异常时，分析日内各时段的标准差，识别波动峰值时段</p>
     */
    private void detectPeriodicFluctuationWindow(DeviceState state, DeviceDiagnosis diagnosis) {
        if (!diagnosis.isHasPeriodicAnomaly()) {
            return;
        }

        List<DisplacementResult> history = state.getDownsampledHistory();
        if (history.size() < 144) {
            return;
        }

        // 按10分钟槽位统计标准差
        int slots = 144; // 24h * 6个10分钟
        int samplesPerSlot = history.size() / slots;
        if (samplesPerSlot < 3) {
            return;
        }

        double[] slotStd = new double[slots];
        double maxStd = 0;
        int maxStdSlot = 0;

        for (int s = 0; s < slots; s++) {
            double sum = 0, sumSq = 0;
            int count = 0;
            for (int i = s; i < history.size(); i += slots) {
                if (i < history.size()) {
                    double v = history.get(i).getdNorth();
                    sum += v;
                    sumSq += v * v;
                    count++;
                }
            }
            if (count > 1) {
                double mean = sum / count;
                double std = Math.sqrt((sumSq - sum * mean) / (count - 1));
                slotStd[s] = std;
                if (std > maxStd) {
                    maxStd = std;
                    maxStdSlot = s;
                }
            }
        }

        if (maxStd > 0.001) {
            int startMinute = maxStdSlot * 10;
            int endMinute = startMinute + 120; // 默认2小时窗口
            diagnosis.setInPeriodicFluctuation(true);
            diagnosis.setPeriodicStartMinute(startMinute);
            diagnosis.setPeriodicEndMinute(endMinute);
        }
    }

    /**
     * 生成反馈信号（遵守冷却时间限制）
     */
    private void generateFeedbackSignal(DeviceDiagnosis diagnosis) {
        long now = System.currentTimeMillis();

        // 反馈信号更新间隔限制
        if (now - lastFeedbackTime < config.feedbackIntervalSeconds * 1000L) {
            return;
        }

        // 模式切换冷却检测
        boolean thermalNowActive = diagnosis.isThermalModeActive();
        if (thermalNowActive != thermalModePreviouslyActive) {
            // 模式切换中，检查冷却时间
            if (now - lastFeedbackTime < config.modeTransitionCooldown * 1000L) {
                // 冷却期内，保持旧状态
                diagnosis.setThermalModeActive(thermalModePreviouslyActive);
                return;
            }
            thermalModePreviouslyActive = thermalNowActive;
        }

        lastFeedbackTime = now;
        diagnosis.setFeedbackTimestamp(now);
    }

    /**
     * Pearson相关系数
     */
    private double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;
        double meanX = 0, meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        double denom = Math.sqrt(varX * varY);
        return denom > 1e-10 ? cov / denom : 0;
    }

    /**
     * 温度处理（基于日周期变化）
     * <p>维护24小时温度剖面，比较当前温度与同日同时段正常值的偏差，
     * 偏差超过阈值时触发密集采样。日周期模型未成熟时回退到步进检测。</p>
     *
     * @param temperature 当前温度（℃）
     * @param state       设备状态
     */
    public void processTemperature(double temperature, DeviceState state) {
        if (temperature == config.tempDefaultValue) {
            return;
        }

        // EMA平滑
        if (emaTemperature < -900) {
            emaTemperature = temperature;
        } else {
            emaTemperature = config.tempEMAFactor * temperature
                    + (1 - config.tempEMAFactor) * emaTemperature;
        }

        // 确定当前时间槽（0 ~ slots-1）
        int slot = getTimeSlot();

        // 更新日周期剖面
        updateDailyProfile(slot, emaTemperature);

        // 判断是否触发密集采样
        if (tempProfileMature) {
            // 日周期模型已成熟：比较当前值与同时段正常值的偏差
            checkDailyDeviation(slot, emaTemperature, state);
        } else {
            // 回退模式：步进变化检测
            checkStepChange(emaTemperature, state);
        }

        state.setLastTemperature(temperature);

        // 存入温度历史（用于相关性计算）
        tempHistory.addLast(temperature);
        while (tempHistory.size() > MAX_TEMP_HISTORY) {
            tempHistory.removeFirst();
        }
    }

    /**
     * 根据当前时间计算日周期槽位
     */
    private int getTimeSlot() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        int minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return (int) ((long) minuteOfDay * config.tempProfileSlots / 1440);
    }

    /**
     * 更新日周期温度剖面（EMA累加）
     */
    private void updateDailyProfile(int slot, double emaTemp) {
        if (tempDailyProfile[slot] <= config.tempDefaultValue + 1) {
            tempDailyProfile[slot] = emaTemp;
        } else {
            tempDailyProfile[slot] = config.tempEMAFactor * emaTemp
                    + (1 - config.tempEMAFactor) * tempDailyProfile[slot];
        }
        tempDailyCount[slot]++;
        tempProfileTotalSamples++;

        // 成熟度判断：每个槽位至少有 initDays 天数据
        if (!tempProfileMature) {
            int minSamplesPerSlot = config.tempProfileInitDays
                    * (1440 / config.tempProfileSlots); // 每天每槽的理论采样数
            boolean allSlotsFilled = true;
            for (int i = 0; i < config.tempProfileSlots; i++) {
                if (tempDailyCount[i] < minSamplesPerSlot / 2) { // 允许50%缺失
                    allSlotsFilled = false;
                    break;
                }
            }
            tempProfileMature = allSlotsFilled;
        }
    }

    /**
     * 日周期偏离检测：当前温度与同时段正常值的偏差
     */
    private void checkDailyDeviation(int slot, double emaTemp, DeviceState state) {
        double expected = tempDailyProfile[slot];
        double deviation = Math.abs(emaTemp - expected);

        if (deviation > config.tempDailyDeviationThreshold) {
            tempHysteresisCount++;
            if (tempHysteresisCount >= config.tempHysteresisSamples) {
                long now = System.currentTimeMillis();
                if (now - lastDenseRecordTime > config.tempCooldownSeconds * 1000L) {
                    state.setDenseRecordActive(true);
                    lastDenseRecordTime = now;
                    tempHysteresisCount = 0;
                }
            }
        } else {
            tempHysteresisCount = 0;
            state.setDenseRecordActive(false);
        }
    }

    /**
     * 回退模式：步进温度变化检测（日周期模型未成熟时使用）
     */
    private void checkStepChange(double emaTemp, DeviceState state) {
        double diff = Math.abs(emaTemp - state.getLastTemperature());
        if (diff > config.tempChangeThreshold) {
            tempHysteresisCount++;
            if (tempHysteresisCount >= config.tempHysteresisSamples) {
                long now = System.currentTimeMillis();
                if (now - lastDenseRecordTime > config.tempCooldownSeconds * 1000L) {
                    state.setDenseRecordActive(true);
                    lastDenseRecordTime = now;
                    tempHysteresisCount = 0;
                }
            }
        } else {
            tempHysteresisCount = 0;
            state.setDenseRecordActive(false);
        }
    }

    public void addToDownsampledHistory(DisplacementResult result, DeviceState state) {
        List<DisplacementResult> history = state.getDownsampledHistory();
        history.add(result);
        while (history.size() > state.getDownsampledHistoryCapacity()) {
            history.remove(0);
        }
    }

    public void addAnomalyEvent(String deviceId, String event, DeviceState state) {
        List<String> events = state.getAnomalyEventLog();
        events.add(System.currentTimeMillis() + ": " + event);
        while (events.size() > state.getAnomalyEventLogCapacity()) {
            events.remove(0);
        }
    }

    private void checkJittering(DeviceState state, DeviceDiagnosis diagnosis) {
        List<DisplacementResult> history = state.getDownsampledHistory();
        if (history.size() < 10) {
            return;
        }
        int count = Math.min(10, history.size());
        double[] nValues = new double[count];
        for (int i = 0; i < count; i++) {
            nValues[i] = history.get(history.size() - count + i).getdNorth();
        }
        double mean = 0;
        for (double v : nValues) {
            mean += v;
        }
        mean /= count;
        double maxDev = 0;
        double minDev = Double.MAX_VALUE;
        for (double v : nValues) {
            double dev = Math.abs(v - mean);
            maxDev = Math.max(maxDev, dev);
            minDev = Math.min(minDev, dev);
        }
        double range = maxDev - minDev;
        if (range > 0 && maxDev <= config.jitterThreshold) {
            diagnosis.setJittering(true);
            diagnosis.setJitterRange(maxDev);
            diagnosis.setJitterDuration(System.currentTimeMillis() - state.getLastUpdateTime());
        }
    }

    private void checkTrend(DeviceState state, DeviceDiagnosis diagnosis) {
        List<DisplacementResult> history = state.getDownsampledHistory();
        if (history.size() < 12) {
            return;
        }
        int n = Math.min(history.size(), 144);
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
            y[i] = history.get(history.size() - n + i).getdNorth();
        }
        double meanX = 0, meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            num += (x[i] - meanX) * (y[i] - meanY);
            den += (x[i] - meanX) * (x[i] - meanX);
        }
        if (den > 1e-10) {
            double slope = num / den;
            if (Math.abs(slope) > config.trendThreshold) {
                diagnosis.setHasPeriodicAnomaly(true);
            }
        }
    }

    private void checkPeriodicAnomaly(DeviceState state, DeviceDiagnosis diagnosis) {
        List<DisplacementResult> history = state.getDownsampledHistory();
        if (history.size() < 144) {
            return;
        }
        double[] values = new double[history.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = history.get(i).getdNorth();
        }
        double corr = autoCorrelation(values, 144);
        if (corr > 0.5) {
            diagnosis.setHasPeriodicAnomaly(true);
            diagnosis.setPeriodicPattern(PeriodicPattern.DAILY_DRIFT);
        }
    }

    private double autoCorrelation(double[] data, int lag) {
        if (data.length <= lag) {
            return 0;
        }
        double mean = 0;
        for (double v : data) {
            mean += v;
        }
        mean /= data.length;
        double num = 0, den = 0;
        for (int i = 0; i < data.length - lag; i++) {
            num += (data[i] - mean) * (data[i + lag] - mean);
        }
        for (int i = 0; i < data.length; i++) {
            den += (data[i] - mean) * (data[i] - mean);
        }
        return den > 0 ? num / den : 0;
    }

    private void updateHealth(DeviceDiagnosis diagnosis) {
        if (diagnosis.isThermalModeActive() || diagnosis.isHasPeriodicAnomaly() || diagnosis.isJittering()) {
            diagnosis.setHealth(HealthStatus.ABNORMAL);
        } else {
            diagnosis.setHealth(HealthStatus.HEALTHY);
        }
    }
}