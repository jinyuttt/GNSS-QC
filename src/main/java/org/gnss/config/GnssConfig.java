package org.gnss.config;

/**
 * GNSS解算配置
 * <p>控制RTK定位的核心参数，包括天线改正、对流层/电离层模型、质量门禁等</p>
 */
public class GnssConfig {

    /** 天线相位中心改正文件路径（ANTEX格式，如igs14.atx），默认：无 */
    private String antexFilePath;

    /** 对流层延迟模型，默认：EST（实时估计天顶对流层延迟ZTD） */
    private TropoModel tropoModel = TropoModel.EST;

    /** 电离层延迟模型，默认：IFLC（双频消电离层组合） */
    private IonoModel ionoModel = IonoModel.IFLC;

    /** 是否只接受固定解（FIX），默认：true */
    private boolean onlyFixedSolution = true;

    /** 最小Ratio值（FIX解准入阈值），默认：3.0 */
    private double minRatio = 3.0;

    /** 卫星高度角掩蔽角（度），默认：10.0° */
    private double elevationMask = 10.0;

    public GnssConfig() {
    }

    public String getAntexFilePath() {
        return antexFilePath;
    }

    public void setAntexFilePath(String antexFilePath) {
        this.antexFilePath = antexFilePath;
    }

    public TropoModel getTropoModel() {
        return tropoModel;
    }

    public void setTropoModel(TropoModel tropoModel) {
        this.tropoModel = tropoModel;
    }

    public IonoModel getIonoModel() {
        return ionoModel;
    }

    public void setIonoModel(IonoModel ionoModel) {
        this.ionoModel = ionoModel;
    }

    public boolean isOnlyFixedSolution() {
        return onlyFixedSolution;
    }

    public void setOnlyFixedSolution(boolean onlyFixedSolution) {
        this.onlyFixedSolution = onlyFixedSolution;
    }

    public double getMinRatio() {
        return minRatio;
    }

    public void setMinRatio(double minRatio) {
        this.minRatio = minRatio;
    }

    public double getElevationMask() {
        return elevationMask;
    }

    public void setElevationMask(double elevationMask) {
        this.elevationMask = elevationMask;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private GnssConfig config = new GnssConfig();

        public Builder antexFilePath(String path) {
            config.antexFilePath = path;
            return this;
        }

        public Builder tropoModel(TropoModel model) {
            config.tropoModel = model;
            return this;
        }

        public Builder ionoModel(IonoModel model) {
            config.ionoModel = model;
            return this;
        }

        public Builder onlyFixedSolution(boolean val) {
            config.onlyFixedSolution = val;
            return this;
        }

        public Builder minRatio(double val) {
            config.minRatio = val;
            return this;
        }

        public Builder elevationMask(double val) {
            config.elevationMask = val;
            return this;
        }

        public GnssConfig build() {
            return config;
        }
    }
}