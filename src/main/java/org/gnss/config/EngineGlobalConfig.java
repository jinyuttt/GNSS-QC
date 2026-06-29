package org.gnss.config;

/**
 * 引擎全局配置
 * <p>承载引擎级全局开关，控制可选扩展模块的启停</p>
 */
public class EngineGlobalConfig {

    /** 第六层空间联合校验总开关：false=默认关闭，true=启用模块 */
    private boolean enableSpatialCheck = false;

    /** 第七层综合仲裁总开关：false=默认关闭，true=启用模块 */
    private boolean enableLayer7Arbitration = false;

    public EngineGlobalConfig() {
    }

    public boolean isEnableSpatialCheck() {
        return enableSpatialCheck;
    }

    public void setEnableSpatialCheck(boolean enableSpatialCheck) {
        this.enableSpatialCheck = enableSpatialCheck;
    }

    public boolean isEnableLayer7Arbitration() {
        return enableLayer7Arbitration;
    }

    public void setEnableLayer7Arbitration(boolean enableLayer7Arbitration) {
        this.enableLayer7Arbitration = enableLayer7Arbitration;
    }
}