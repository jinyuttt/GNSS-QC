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

    public DeviceState() {
    }

    public LinkedList<Double> getNorthWindow() {
        return northWindow;
    }

    public LinkedList<Double> getEastWindow() {
        return eastWindow;
    }

    public LinkedList<Double> getUpWindow() {
        return upWindow;
    }

    public double getLastValidNorth() {
        return lastValidNorth;
    }

    public void setLastValidNorth(double lastValidNorth) {
        this.lastValidNorth = lastValidNorth;
    }

    public double getLastValidEast() {
        return lastValidEast;
    }

    public void setLastValidEast(double lastValidEast) {
        this.lastValidEast = lastValidEast;
    }

    public double getLastValidUp() {
        return lastValidUp;
    }

    public void setLastValidUp(double lastValidUp) {
        this.lastValidUp = lastValidUp;
    }

    public double getPrevValidNorth() {
        return prevValidNorth;
    }

    public void setPrevValidNorth(double prevValidNorth) {
        this.prevValidNorth = prevValidNorth;
    }

    public double getPrevValidEast() {
        return prevValidEast;
    }

    public void setPrevValidEast(double prevValidEast) {
        this.prevValidEast = prevValidEast;
    }

    public double getPrevValidUp() {
        return prevValidUp;
    }

    public void setPrevValidUp(double prevValidUp) {
        this.prevValidUp = prevValidUp;
    }

    public LinkedList<Double> getFastBaselineNorth() {
        return fastBaselineNorth;
    }

    public LinkedList<Double> getFastBaselineEast() {
        return fastBaselineEast;
    }

    public LinkedList<Double> getFastBaselineUp() {
        return fastBaselineUp;
    }

    public LinkedList<Double> getSlowBaselineNorth() {
        return slowBaselineNorth;
    }

    public LinkedList<Double> getSlowBaselineEast() {
        return slowBaselineEast;
    }

    public LinkedList<Double> getSlowBaselineUp() {
        return slowBaselineUp;
    }

    public List<DisplacementResult> getDownsampledHistory() {
        return downsampledHistory;
    }

    public int getDownsampledHistoryCapacity() {
        return downsampledHistoryCapacity;
    }

    public void setDownsampledHistoryCapacity(int downsampledHistoryCapacity) {
        this.downsampledHistoryCapacity = downsampledHistoryCapacity;
    }

    public double getLastTemperature() {
        return lastTemperature;
    }

    public void setLastTemperature(double lastTemperature) {
        this.lastTemperature = lastTemperature;
    }

    public boolean isDenseRecordActive() {
        return denseRecordActive;
    }

    public void setDenseRecordActive(boolean denseRecordActive) {
        this.denseRecordActive = denseRecordActive;
    }

    public List<String> getAnomalyEventLog() {
        return anomalyEventLog;
    }

    public int getAnomalyEventLogCapacity() {
        return anomalyEventLogCapacity;
    }

    public void setAnomalyEventLogCapacity(int anomalyEventLogCapacity) {
        this.anomalyEventLogCapacity = anomalyEventLogCapacity;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public DeviceDiagnosis getLastDiagnosis() {
        return lastDiagnosis;
    }

    public void setLastDiagnosis(DeviceDiagnosis lastDiagnosis) {
        this.lastDiagnosis = lastDiagnosis;
    }

    public boolean isLastValidInitialized() {
        return lastValidInitialized;
    }

    public void setLastValidInitialized(boolean lastValidInitialized) {
        this.lastValidInitialized = lastValidInitialized;
    }

    // ========== 第五层阶跃观察状态 getter/setter ==========

    public int getStepObservationCount() {
        return stepObservationCount;
    }

    public void setStepObservationCount(int stepObservationCount) {
        this.stepObservationCount = stepObservationCount;
    }

    public double getStepCandidateNorth() {
        return stepCandidateNorth;
    }

    public void setStepCandidateNorth(double stepCandidateNorth) {
        this.stepCandidateNorth = stepCandidateNorth;
    }

    public double getStepCandidateEast() {
        return stepCandidateEast;
    }

    public void setStepCandidateEast(double stepCandidateEast) {
        this.stepCandidateEast = stepCandidateEast;
    }

    public double getStepCandidateUp() {
        return stepCandidateUp;
    }

    public void setStepCandidateUp(double stepCandidateUp) {
        this.stepCandidateUp = stepCandidateUp;
    }

    public long getStepStartTime() {
        return stepStartTime;
    }

    public void setStepStartTime(long stepStartTime) {
        this.stepStartTime = stepStartTime;
    }

    public double getInitialBaselineNorth() {
        return initialBaselineNorth;
    }

    public double getInitialBaselineEast() {
        return initialBaselineEast;
    }

    public double getInitialBaselineUp() {
        return initialBaselineUp;
    }

    public boolean isInitialBaselineEstablished() {
        return initialBaselineEstablished;
    }

    /**
     * 累积初始基线（前10个FIX解）
     * <p>累积满10个后自动计算均值并标记基线已建立</p>
     *
     * @param north 北向位移
     * @param east  东向位移
     * @param up    天向位移
     */
    public void accumulateInitialBaseline(double north, double east, double up) {
        if (initialBaselineEstablished) {
            return;
        }
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

    public LinkedList<DirectionRecord> getJumpDirectionHistory() {
        return jumpDirectionHistory;
    }

    public int getConsecutiveSameDirectionCount() {
        return consecutiveSameDirectionCount;
    }

    public long getConsecutiveSameDirectionStartTime() {
        return consecutiveSameDirectionStartTime;
    }

    /**
     * 记录当前历元的跳变方向
     *
     * @param signN          北向跳变方向符号（+1/-1/0）
     * @param signE          东向跳变方向符号（+1/-1/0）
     * @param signU          天向跳变方向符号（+1/-1/0）
     * @param maxHistorySize 历史记录最大容量（建议设为 trendQuickReleaseCount * 2）
     */
    public void recordJumpDirection(double signN, double signE, double signU, int maxHistorySize) {
        DirectionRecord rec = new DirectionRecord(
                System.currentTimeMillis(),
                signN,
                signE,
                signU
        );
        jumpDirectionHistory.addLast(rec);
        while (jumpDirectionHistory.size() > maxHistorySize) {
            jumpDirectionHistory.removeFirst();
        }
    }

    /**
     * 重置连续同向趋势计数（跳变链中断时调用）
     */
    public void resetConsecutiveSameDirection() {
        consecutiveSameDirectionCount = 0;
        consecutiveSameDirectionStartTime = 0;
    }

    /**
     * 更新连续同向趋势计数
     *
     * @param sameDirection 当前历元是否与历史同向
     */
    public void updateConsecutiveSameDirection(boolean sameDirection) {
        if (sameDirection) {
            if (consecutiveSameDirectionCount == 0) {
                consecutiveSameDirectionStartTime = System.currentTimeMillis();
            }
            consecutiveSameDirectionCount++;
        } else {
            consecutiveSameDirectionCount = 0;
            consecutiveSameDirectionStartTime = 0;
        }
    }

    // ==================== 快照转换 ====================

    /**
     * 转换为持久化快照
     * <p>将内存状态序列化为可持久化的 DeviceStateSnapshot</p>
     *
     * @param deviceId 设备ID
     * @return 设备状态快照
     */
    public DeviceStateSnapshot toSnapshot(String deviceId) {
        DeviceStateSnapshot snapshot = new DeviceStateSnapshot();
        snapshot.setDeviceId(deviceId);
        snapshot.setSnapshotTime(java.time.Instant.now());

        // 上一合法值
        snapshot.setLastValidNorth(lastValidNorth);
        snapshot.setLastValidEast(lastValidEast);
        snapshot.setLastValidUp(lastValidUp);

        // 双基线中位数
        snapshot.setFastBaselineNorth(median(fastBaselineNorth));
        snapshot.setFastBaselineEast(median(fastBaselineEast));
        snapshot.setFastBaselineUp(median(fastBaselineUp));
        snapshot.setSlowBaselineNorth(median(slowBaselineNorth));
        snapshot.setSlowBaselineEast(median(slowBaselineEast));
        snapshot.setSlowBaselineUp(median(slowBaselineUp));

        // 温度状态
        snapshot.setLastTemperature(lastTemperature);
        snapshot.setDenseMode(denseRecordActive);
        snapshot.setDenseCounter(0);

        // 阶跃观察状态
        snapshot.setStepObservationCount(stepObservationCount);
        snapshot.setStepCandidateNorth(stepCandidateNorth);
        snapshot.setStepCandidateEast(stepCandidateEast);
        snapshot.setStepCandidateUp(stepCandidateUp);
        snapshot.setStepStartTime(stepStartTime);

        // 初始基线
        snapshot.setInitialBaselineEstablished(initialBaselineEstablished);
        snapshot.setInitialBaselineNorth(initialBaselineNorth);
        snapshot.setInitialBaselineEast(initialBaselineEast);
        snapshot.setInitialBaselineUp(initialBaselineUp);
        snapshot.setInitialBaselineCount(initialBaselineFixCount);

        return snapshot;
    }

    private static double median(List<Double> list) {
        if (list == null || list.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        } else {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        }
    }

    // ==================== DirectionRecord 内部类 ====================

    /**
     * 跳变方向记录
     * <p>记录单个历元N/E/U三个方向的跳变方向符号，用于同向趋势判断</p>
     */
    public static class DirectionRecord {
        /** 时间戳（毫秒） */
        private final long timestamp;
        /** 北向跳变方向符号（+1/-1/0） */
        private final double signN;
        /** 东向跳变方向符号（+1/-1/0） */
        private final double signE;
        /** 天向跳变方向符号（+1/-1/0） */
        private final double signU;

        public DirectionRecord(long timestamp, double signN, double signE, double signU) {
            this.timestamp = timestamp;
            this.signN = signN;
            this.signE = signE;
            this.signU = signU;
        }

        /**
         * 判断三个方向是否完全同向
         * <p>忽略零值（未变化的方向），只要非零方向同向即视为同向</p>
         *
         * @param otherSignN 待比较的北向符号
         * @param otherSignE 待比较的东向符号
         * @param otherSignU 待比较的天向符号
         * @return true=三维同向
         */
        public boolean isSameDirection(double otherSignN, double otherSignE, double otherSignU) {
            boolean nSame = signN == 0 || otherSignN == 0 || signN == otherSignN;
            boolean eSame = signE == 0 || otherSignE == 0 || signE == otherSignE;
            boolean uSame = signU == 0 || otherSignU == 0 || signU == otherSignU;
            return nSame && eSame && uSame;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public double getSignN() {
            return signN;
        }

        public double getSignE() {
            return signE;
        }

        public double getSignU() {
            return signU;
        }
    }
}