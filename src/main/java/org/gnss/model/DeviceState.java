package org.gnss.model;

import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备状态（引擎内存缓存）
 * <p>按设备ID管理：滑动窗口、双基线、降采样历史、温度状态、异常事件日志</p>
 */
public class DeviceState {

    /** N方向滑动窗口（20条），默认：空 */
    private LinkedList<Double> northWindow = new LinkedList<>();

    /** E方向滑动窗口（20条），默认：空 */
    private LinkedList<Double> eastWindow = new LinkedList<>();

    /** U方向滑动窗口（20条），默认：空 */
    private LinkedList<Double> upWindow = new LinkedList<>();

    /** 上一合法北向位移值（米），默认：0.0 */
    private double lastValidNorth;

    /** 上一合法东向位移值（米），默认：0.0 */
    private double lastValidEast;

    /** 上一合法天向位移值（米），默认：0.0 */
    private double lastValidUp;

    /** 上上一次合法北向位移值（米），默认：0.0 */
    private double prevValidNorth;

    /** 上上一次合法东向位移值（米），默认：0.0 */
    private double prevValidEast;

    /** 上上一次合法天向位移值（米），默认：0.0 */
    private double prevValidUp;

    /** 快速基线N方向（5分钟中位数），默认：空 */
    private LinkedList<Double> fastBaselineNorth = new LinkedList<>();

    /** 快速基线E方向（5分钟中位数），默认：空 */
    private LinkedList<Double> fastBaselineEast = new LinkedList<>();

    /** 快速基线U方向（5分钟中位数），默认：空 */
    private LinkedList<Double> fastBaselineUp = new LinkedList<>();

    /** 慢速基线N方向（24小时中位数），默认：空 */
    private LinkedList<Double> slowBaselineNorth = new LinkedList<>();

    /** 慢速基线E方向（24小时中位数），默认：空 */
    private LinkedList<Double> slowBaselineEast = new LinkedList<>();

    /** 慢速基线U方向（24小时中位数），默认：空 */
    private LinkedList<Double> slowBaselineUp = new LinkedList<>();

    /** 降采样历史（10分钟1条，144条环形缓冲），默认：空 */
    private List<DisplacementResult> downsampledHistory = new ArrayList<>();

    /** 降采样历史最大容量，默认：144 */
    private int downsampledHistoryCapacity = 144;

    /** 上次温度值（℃），默认：-999.0（无数据） */
    private double lastTemperature = -999.0;

    /** 是否处于密集记录状态，默认：false */
    private boolean denseRecordActive;

    /** 异常事件日志（500条环形缓冲），默认：空 */
    private List<String> anomalyEventLog = new ArrayList<>();

    /** 异常事件日志最大容量，默认：500 */
    private int anomalyEventLogCapacity = 500;

    /** 最后更新时间戳（毫秒），默认：0 */
    private long lastUpdateTime;

    /** 上次诊断结果（用于清洗模块动态阈值调整），默认：null */
    private DeviceDiagnosis lastDiagnosis;

    /** 上一合法值是否已初始化（区分真实0值与默认值），默认：false */
    private boolean lastValidInitialized;

    // ========== 第五层阶跃观察状态 ==========

    /** 阶跃观察计数（触发后连续历元数），默认：0 */
    private int stepObservationCount;

    /** 阶跃候选北向位移（米），默认：0.0 */
    private double stepCandidateNorth;

    /** 阶跃候选东向位移（米），默认：0.0 */
    private double stepCandidateEast;

    /** 阶跃候选天向位移（米），默认：0.0 */
    private double stepCandidateUp;

    /** 阶跃触发时间戳（毫秒），默认：0 */
    private long stepStartTime;

    /** 初始基线北向（前10个FIX解均值），默认：0.0 */
    private double initialBaselineNorth;

    /** 初始基线东向（前10个FIX解均值），默认：0.0 */
    private double initialBaselineEast;

    /** 初始基线天向（前10个FIX解均值），默认：0.0 */
    private double initialBaselineUp;

    /** 初始基线是否已建立，默认：false */
    private boolean initialBaselineEstablished;

    /** 初始基线FIX解计数器（用于计算前10个FIX解均值），默认：0 */
    private int initialBaselineFixCount;

    /** 初始基线北向累计值（米），默认：0.0 */
    private double initialBaselineNorthSum;

    /** 初始基线东向累计值（米），默认：0.0 */
    private double initialBaselineEastSum;

    /** 初始基线天向累计值（米），默认：0.0 */
    private double initialBaselineUpSum;

    /** 跳变方向历史记录（最近N个历元），默认：空 */
    private final LinkedList<DirectionRecord> jumpDirectionHistory = new LinkedList<>();

    /** 连续同向计数，默认：0 */
    private int consecutiveSameDirectionCount;

    /** 连续同向起始时间戳（毫秒），默认：0 */
    private long consecutiveSameDirectionStartTime;

    // ========== L0 小波去噪环形缓冲 ==========

    /** L0 N方向环形缓冲，默认：空 */
    private double[] waveletBufferN;

    /** L0 E方向环形缓冲，默认：空 */
    private double[] waveletBufferE;

    /** L0 U方向环形缓冲，默认：空 */
    private double[] waveletBufferU;

    /** L0 环形缓冲写入位置，默认：0 */
    private int waveletBufferPos;

    /** L0 环形缓冲已填充数量，默认：0 */
    private int waveletBufferFilled;

    /** L0 窗口大小，默认：0（未初始化） */
    private int waveletWindowSize;

    /** L0 上次去噪结果N，默认：null（未计算） */
    private Double denoisedNorth;

    /** L0 上次去噪结果E，默认：null（未计算） */
    private Double denoisedEast;

    /** L0 上次去噪结果U，默认：null（未计算） */
    private Double denoisedUp;

    // ========== L2 CUSUM 累加器 ==========

    /** CUSUM 正向累加器N，默认：0.0 */
    private double cusumPlusN;

    /** CUSUM 负向累加器N，默认：0.0 */
    private double cusumMinusN;

    /** CUSUM 正向累加器E，默认：0.0 */
    private double cusumPlusE;

    /** CUSUM 负向累加器E，默认：0.0 */
    private double cusumMinusE;

    /** CUSUM 正向累加器U，默认：0.0 */
    private double cusumPlusU;

    /** CUSUM 负向累加器U，默认：0.0 */
    private double cusumMinusU;

    /** CUSUM 漂移标记，默认：false */
    private boolean driftSuspicion;

    // ========== L5 LOESS 缓存 ==========

    /** LOESS慢基线N缓存，默认：null（未计算） */
    private Double loessSlowBaselineNorth;

    /** LOESS慢基线E缓存，默认：null（未计算） */
    private Double loessSlowBaselineEast;

    /** LOESS慢基线U缓存，默认：null（未计算） */
    private Double loessSlowBaselineUp;

    /** LOESS上次重算时的历元计数，默认：0 */
    private int loessLastRecalcEpoch;

    // ========== L4 连续粗差跟踪 ==========

    /** 连续粗差计数，默认：0 */
    private int consecutiveOutlierCount;

    /** 连续粗差起始索引（在清洗批次中），默认：-1 */
    private int consecutiveOutlierStart;

    public DeviceState() {
    }

    // ==================== 原有 getter/setter ====================

    public LinkedList<Double> getNorthWindow() { return northWindow; }
    public LinkedList<Double> getEastWindow() { return eastWindow; }
    public LinkedList<Double> getUpWindow() { return upWindow; }

    public double getLastValidNorth() { return lastValidNorth; }
    public void setLastValidNorth(double lastValidNorth) { this.lastValidNorth = lastValidNorth; }

    public double getLastValidEast() { return lastValidEast; }
    public void setLastValidEast(double lastValidEast) { this.lastValidEast = lastValidEast; }

    public double getLastValidUp() { return lastValidUp; }
    public void setLastValidUp(double lastValidUp) { this.lastValidUp = lastValidUp; }

    public double getPrevValidNorth() { return prevValidNorth; }
    public void setPrevValidNorth(double prevValidNorth) { this.prevValidNorth = prevValidNorth; }

    public double getPrevValidEast() { return prevValidEast; }
    public void setPrevValidEast(double prevValidEast) { this.prevValidEast = prevValidEast; }

    public double getPrevValidUp() { return prevValidUp; }
    public void setPrevValidUp(double prevValidUp) { this.prevValidUp = prevValidUp; }

    public LinkedList<Double> getFastBaselineNorth() { return fastBaselineNorth; }
    public LinkedList<Double> getFastBaselineEast() { return fastBaselineEast; }
    public LinkedList<Double> getFastBaselineUp() { return fastBaselineUp; }

    public LinkedList<Double> getSlowBaselineNorth() { return slowBaselineNorth; }
    public LinkedList<Double> getSlowBaselineEast() { return slowBaselineEast; }
    public LinkedList<Double> getSlowBaselineUp() { return slowBaselineUp; }

    public List<DisplacementResult> getDownsampledHistory() { return downsampledHistory; }
    public int getDownsampledHistoryCapacity() { return downsampledHistoryCapacity; }
    public void setDownsampledHistoryCapacity(int cap) { this.downsampledHistoryCapacity = cap; }

    public double getLastTemperature() { return lastTemperature; }
    public void setLastTemperature(double t) { this.lastTemperature = t; }

    public boolean isDenseRecordActive() { return denseRecordActive; }
    public void setDenseRecordActive(boolean a) { this.denseRecordActive = a; }

    public List<String> getAnomalyEventLog() { return anomalyEventLog; }
    public int getAnomalyEventLogCapacity() { return anomalyEventLogCapacity; }
    public void setAnomalyEventLogCapacity(int cap) { this.anomalyEventLogCapacity = cap; }

    public long getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(long t) { this.lastUpdateTime = t; }

    public DeviceDiagnosis getLastDiagnosis() { return lastDiagnosis; }
    public void setLastDiagnosis(DeviceDiagnosis d) { this.lastDiagnosis = d; }

    public boolean isLastValidInitialized() { return lastValidInitialized; }
    public void setLastValidInitialized(boolean v) { this.lastValidInitialized = v; }

    // ========== 第五层阶跃观察状态 getter/setter ==========

    public int getStepObservationCount() { return stepObservationCount; }
    public void setStepObservationCount(int c) { this.stepObservationCount = c; }

    public double getStepCandidateNorth() { return stepCandidateNorth; }
    public void setStepCandidateNorth(double v) { this.stepCandidateNorth = v; }

    public double getStepCandidateEast() { return stepCandidateEast; }
    public void setStepCandidateEast(double v) { this.stepCandidateEast = v; }

    public double getStepCandidateUp() { return stepCandidateUp; }
    public void setStepCandidateUp(double v) { this.stepCandidateUp = v; }

    public long getStepStartTime() { return stepStartTime; }
    public void setStepStartTime(long t) { this.stepStartTime = t; }

    public double getInitialBaselineNorth() { return initialBaselineNorth; }
    public double getInitialBaselineEast() { return initialBaselineEast; }
    public double getInitialBaselineUp() { return initialBaselineUp; }
    public boolean isInitialBaselineEstablished() { return initialBaselineEstablished; }

    public void accumulateInitialBaseline(double north, double east, double up) {
        if (initialBaselineEstablished) return;
        initialBaselineNorthSum += north;
        initialBaselineEastSum += east;
        initialBaselineUpSum += up;
        initialBaselineFixCount++;
        if (initialBaselineFixCount >= 10) {
            initialBaselineNorth = initialBaselineNorthSum / initialBaselineFixCount;
            initialBaselineEast = initialBaselineEastSum / initialBaselineFixCount;
            initialBaselineUp = initialBaselineUpSum / initialBaselineFixCount;
            initialBaselineEstablished = true;
        }
    }

    // ========== 跳变方向历史记录 getter/setter ==========

    public LinkedList<DirectionRecord> getJumpDirectionHistory() { return jumpDirectionHistory; }
    public int getConsecutiveSameDirectionCount() { return consecutiveSameDirectionCount; }
    public long getConsecutiveSameDirectionStartTime() { return consecutiveSameDirectionStartTime; }

    public void recordJumpDirection(double signN, double signE, double signU, int maxHistorySize) {
        DirectionRecord rec = new DirectionRecord(System.currentTimeMillis(), signN, signE, signU);
        jumpDirectionHistory.addLast(rec);
        while (jumpDirectionHistory.size() > maxHistorySize) jumpDirectionHistory.removeFirst();
    }

    public void resetConsecutiveSameDirection() {
        consecutiveSameDirectionCount = 0;
        consecutiveSameDirectionStartTime = 0;
    }

    public void updateConsecutiveSameDirection(boolean sameDirection) {
        if (sameDirection) {
            if (consecutiveSameDirectionCount == 0) consecutiveSameDirectionStartTime = System.currentTimeMillis();
            consecutiveSameDirectionCount++;
        } else {
            consecutiveSameDirectionCount = 0;
            consecutiveSameDirectionStartTime = 0;
        }
    }

    // ========== L0 小波去噪 getter/setter ==========

    public double[] getWaveletBufferN() { return waveletBufferN; }
    public void setWaveletBufferN(double[] b) { this.waveletBufferN = b; }

    public double[] getWaveletBufferE() { return waveletBufferE; }
    public void setWaveletBufferE(double[] b) { this.waveletBufferE = b; }

    public double[] getWaveletBufferU() { return waveletBufferU; }
    public void setWaveletBufferU(double[] b) { this.waveletBufferU = b; }

    public int getWaveletBufferPos() { return waveletBufferPos; }
    public void setWaveletBufferPos(int p) { this.waveletBufferPos = p; }

    public int getWaveletBufferFilled() { return waveletBufferFilled; }
    public void setWaveletBufferFilled(int f) { this.waveletBufferFilled = f; }

    public int getWaveletWindowSize() { return waveletWindowSize; }
    public void setWaveletWindowSize(int s) { this.waveletWindowSize = s; }

    public Double getDenoisedNorth() { return denoisedNorth; }
    public void setDenoisedNorth(Double v) { this.denoisedNorth = v; }

    public Double getDenoisedEast() { return denoisedEast; }
    public void setDenoisedEast(Double v) { this.denoisedEast = v; }

    public Double getDenoisedUp() { return denoisedUp; }
    public void setDenoisedUp(Double v) { this.denoisedUp = v; }

    // ========== L2 CUSUM getter/setter ==========

    public double getCusumPlusN() { return cusumPlusN; }
    public void setCusumPlusN(double v) { this.cusumPlusN = v; }

    public double getCusumMinusN() { return cusumMinusN; }
    public void setCusumMinusN(double v) { this.cusumMinusN = v; }

    public double getCusumPlusE() { return cusumPlusE; }
    public void setCusumPlusE(double v) { this.cusumPlusE = v; }

    public double getCusumMinusE() { return cusumMinusE; }
    public void setCusumMinusE(double v) { this.cusumMinusE = v; }

    public double getCusumPlusU() { return cusumPlusU; }
    public void setCusumPlusU(double v) { this.cusumPlusU = v; }

    public double getCusumMinusU() { return cusumMinusU; }
    public void setCusumMinusU(double v) { this.cusumMinusU = v; }

    public boolean isDriftSuspicion() { return driftSuspicion; }
    public void setDriftSuspicion(boolean v) { this.driftSuspicion = v; }

    // ========== L5 LOESS 缓存 getter/setter ==========

    public Double getLoessSlowBaselineNorth() { return loessSlowBaselineNorth; }
    public void setLoessSlowBaselineNorth(Double v) { this.loessSlowBaselineNorth = v; }

    public Double getLoessSlowBaselineEast() { return loessSlowBaselineEast; }
    public void setLoessSlowBaselineEast(Double v) { this.loessSlowBaselineEast = v; }

    public Double getLoessSlowBaselineUp() { return loessSlowBaselineUp; }
    public void setLoessSlowBaselineUp(Double v) { this.loessSlowBaselineUp = v; }

    public int getLoessLastRecalcEpoch() { return loessLastRecalcEpoch; }
    public void setLoessLastRecalcEpoch(int e) { this.loessLastRecalcEpoch = e; }

    // ========== L4 连续粗差跟踪 getter/setter ==========

    public int getConsecutiveOutlierCount() { return consecutiveOutlierCount; }
    public void setConsecutiveOutlierCount(int c) { this.consecutiveOutlierCount = c; }

    public int getConsecutiveOutlierStart() { return consecutiveOutlierStart; }
    public void setConsecutiveOutlierStart(int s) { this.consecutiveOutlierStart = s; }

    // ==================== 快照转换 ====================

    public DeviceStateSnapshot toSnapshot(String deviceId) {
        DeviceStateSnapshot snapshot = new DeviceStateSnapshot();
        snapshot.setDeviceId(deviceId);
        snapshot.setSnapshotTime(java.time.Instant.now());
        snapshot.setLastValidNorth(lastValidNorth);
        snapshot.setLastValidEast(lastValidEast);
        snapshot.setLastValidUp(lastValidUp);
        snapshot.setFastBaselineNorth(median(fastBaselineNorth));
        snapshot.setFastBaselineEast(median(fastBaselineEast));
        snapshot.setFastBaselineUp(median(fastBaselineUp));
        snapshot.setSlowBaselineNorth(median(slowBaselineNorth));
        snapshot.setSlowBaselineEast(median(slowBaselineEast));
        snapshot.setSlowBaselineUp(median(slowBaselineUp));
        snapshot.setLastTemperature(lastTemperature);
        snapshot.setDenseMode(denseRecordActive);
        snapshot.setDenseCounter(0);
        snapshot.setStepObservationCount(stepObservationCount);
        snapshot.setStepCandidateNorth(stepCandidateNorth);
        snapshot.setStepCandidateEast(stepCandidateEast);
        snapshot.setStepCandidateUp(stepCandidateUp);
        snapshot.setStepStartTime(stepStartTime);
        snapshot.setInitialBaselineEstablished(initialBaselineEstablished);
        snapshot.setInitialBaselineNorth(initialBaselineNorth);
        snapshot.setInitialBaselineEast(initialBaselineEast);
        snapshot.setInitialBaselineUp(initialBaselineUp);
        snapshot.setInitialBaselineCount(initialBaselineFixCount);
        return snapshot;
    }

    private static double median(List<Double> list) {
        if (list == null || list.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2);
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    // ==================== DirectionRecord 内部类 ====================

    public static class DirectionRecord {
        private final long timestamp;
        private final double signN;
        private final double signE;
        private final double signU;

        public DirectionRecord(long timestamp, double signN, double signE, double signU) {
            this.timestamp = timestamp;
            this.signN = signN;
            this.signE = signE;
            this.signU = signU;
        }

        public boolean isSameDirection(double otherSignN, double otherSignE, double otherSignU) {
            boolean nSame = signN == 0 || otherSignN == 0 || signN == otherSignN;
            boolean eSame = signE == 0 || otherSignE == 0 || signE == otherSignE;
            boolean uSame = signU == 0 || otherSignU == 0 || signU == otherSignU;
            return nSame && eSame && uSame;
        }

        public long getTimestamp() { return timestamp; }
        public double getSignN() { return signN; }
        public double getSignE() { return signE; }
        public double getSignU() { return signU; }
    }
}