package org.gnss.model;

import java.time.Instant;

/**
 * 设备状态快照
 * <p>用于持久化存储设备内存状态，支持重启恢复和LRU淘汰后重新加载</p>
 */
public class DeviceStateSnapshot {

    /** 设备ID */
    private String deviceId;

    /** 快照时间 */
    private Instant snapshotTime;

    /** 上一合法北向位移值（米），默认：0.0 */
    private double lastValidNorth;

    /** 上一合法东向位移值（米），默认：0.0 */
    private double lastValidEast;

    /** 上一合法天向位移值（米），默认：0.0 */
    private double lastValidUp;

    /** 快速基线北向中位数（米），默认：0.0 */
    private double fastBaselineNorth;

    /** 快速基线东向中位数（米），默认：0.0 */
    private double fastBaselineEast;

    /** 快速基线天向中位数（米），默认：0.0 */
    private double fastBaselineUp;

    /** 慢速基线北向中位数（米），默认：0.0 */
    private double slowBaselineNorth;

    /** 慢速基线东向中位数（米），默认：0.0 */
    private double slowBaselineEast;

    /** 慢速基线天向中位数（米），默认：0.0 */
    private double slowBaselineUp;

    /** 上次温度值（℃），默认：-999.0 */
    private double lastTemperature;

    /** 是否处于密集记录状态，默认：false */
    private boolean denseMode;

    /** 密集计数器，默认：0 */
    private int denseCounter;

    /** 阶跃观察计数，默认：0 */
    private int stepObservationCount;

    /** 阶跃候选北向位移（米），默认：0.0 */
    private double stepCandidateNorth;

    /** 阶跃候选东向位移（米），默认：0.0 */
    private double stepCandidateEast;

    /** 阶跃候选天向位移（米），默认：0.0 */
    private double stepCandidateUp;

    /** 阶跃触发时间戳（毫秒），默认：0 */
    private long stepStartTime;

    /** 初始基线是否已建立，默认：false */
    private boolean initialBaselineEstablished;

    /** 初始基线北向（米），默认：0.0 */
    private double initialBaselineNorth;

    /** 初始基线东向（米），默认：0.0 */
    private double initialBaselineEast;

    /** 初始基线天向（米），默认：0.0 */
    private double initialBaselineUp;

    /** 初始基线FIX解计数，默认：0 */
    private int initialBaselineCount;

    public DeviceStateSnapshot() {
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Instant getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(Instant snapshotTime) {
        this.snapshotTime = snapshotTime;
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

    public double getFastBaselineNorth() {
        return fastBaselineNorth;
    }

    public void setFastBaselineNorth(double fastBaselineNorth) {
        this.fastBaselineNorth = fastBaselineNorth;
    }

    public double getFastBaselineEast() {
        return fastBaselineEast;
    }

    public void setFastBaselineEast(double fastBaselineEast) {
        this.fastBaselineEast = fastBaselineEast;
    }

    public double getFastBaselineUp() {
        return fastBaselineUp;
    }

    public void setFastBaselineUp(double fastBaselineUp) {
        this.fastBaselineUp = fastBaselineUp;
    }

    public double getSlowBaselineNorth() {
        return slowBaselineNorth;
    }

    public void setSlowBaselineNorth(double slowBaselineNorth) {
        this.slowBaselineNorth = slowBaselineNorth;
    }

    public double getSlowBaselineEast() {
        return slowBaselineEast;
    }

    public void setSlowBaselineEast(double slowBaselineEast) {
        this.slowBaselineEast = slowBaselineEast;
    }

    public double getSlowBaselineUp() {
        return slowBaselineUp;
    }

    public void setSlowBaselineUp(double slowBaselineUp) {
        this.slowBaselineUp = slowBaselineUp;
    }

    public double getLastTemperature() {
        return lastTemperature;
    }

    public void setLastTemperature(double lastTemperature) {
        this.lastTemperature = lastTemperature;
    }

    public boolean isDenseMode() {
        return denseMode;
    }

    public void setDenseMode(boolean denseMode) {
        this.denseMode = denseMode;
    }

    public int getDenseCounter() {
        return denseCounter;
    }

    public void setDenseCounter(int denseCounter) {
        this.denseCounter = denseCounter;
    }

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

    public boolean isInitialBaselineEstablished() {
        return initialBaselineEstablished;
    }

    public void setInitialBaselineEstablished(boolean initialBaselineEstablished) {
        this.initialBaselineEstablished = initialBaselineEstablished;
    }

    public double getInitialBaselineNorth() {
        return initialBaselineNorth;
    }

    public void setInitialBaselineNorth(double initialBaselineNorth) {
        this.initialBaselineNorth = initialBaselineNorth;
    }

    public double getInitialBaselineEast() {
        return initialBaselineEast;
    }

    public void setInitialBaselineEast(double initialBaselineEast) {
        this.initialBaselineEast = initialBaselineEast;
    }

    public double getInitialBaselineUp() {
        return initialBaselineUp;
    }

    public void setInitialBaselineUp(double initialBaselineUp) {
        this.initialBaselineUp = initialBaselineUp;
    }

    public int getInitialBaselineCount() {
        return initialBaselineCount;
    }

    public void setInitialBaselineCount(int initialBaselineCount) {
        this.initialBaselineCount = initialBaselineCount;
    }
}