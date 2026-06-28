package org.gnss.model;

/**
 * 第6层空间校验 — 组内单设备输入
 * <p>
 * 第6层空间一致性校验需要把整个组的数据一起传入（List），
 * 因为空间校验依赖邻居设备数据做组中位数对比。
 * 每个设备一个实例，包含其当前位移值和状态信息。
 * </p>
 */
public class SpatialGroupInput {

    private String deviceId;
    private double dNorth;
    private double dEast;
    private double dUp;
    private String status;
    private long epochMillis;

    public SpatialGroupInput() {
    }

    public SpatialGroupInput(String deviceId, double dNorth, double dEast,
                             double dUp, String status, long epochMillis) {
        this.deviceId = deviceId;
        this.dNorth = dNorth;
        this.dEast = dEast;
        this.dUp = dUp;
        this.status = status;
        this.epochMillis = epochMillis;
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public double getdNorth() { return dNorth; }
    public void setdNorth(double dNorth) { this.dNorth = dNorth; }

    public double getdEast() { return dEast; }
    public void setdEast(double dEast) { this.dEast = dEast; }

    public double getdUp() { return dUp; }
    public void setdUp(double dUp) { this.dUp = dUp; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getEpochMillis() { return epochMillis; }
    public void setEpochMillis(long epochMillis) { this.epochMillis = epochMillis; }
}