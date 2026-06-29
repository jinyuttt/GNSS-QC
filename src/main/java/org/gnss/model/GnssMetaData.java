package org.gnss.model;

/**
 * GNSS气象元数据
 * <p>辅助数据：温度、气压、湿度，用于温漂分离和更精确的对流层模型</p>
 */
public class GnssMetaData {

    /** 温度（℃），默认：-999.0（无数据） */
    private double temperature = -999.0;

    /** 气压（hPa），默认：-999.0（无数据） */
    private double pressure = -999.0;

    /** 湿度（%），默认：-999.0（无数据） */
    private double humidity = -999.0;

    public GnssMetaData() {
    }

    public GnssMetaData(double temperature, double pressure, double humidity) {
        this.temperature = temperature;
        this.pressure = pressure;
        this.humidity = humidity;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getPressure() {
        return pressure;
    }

    public void setPressure(double pressure) {
        this.pressure = pressure;
    }

    public double getHumidity() {
        return humidity;
    }

    public void setHumidity(double humidity) {
        this.humidity = humidity;
    }
}