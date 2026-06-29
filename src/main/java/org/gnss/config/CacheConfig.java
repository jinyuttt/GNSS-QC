package org.gnss.config;

/**
 * 缓存配置
 * <p>控制设备状态内存缓存的容量和淘汰策略</p>
 */
public class CacheConfig {

    /** 最大缓存设备数，默认：100 */
    public int maxDevices = 100;

    /** 淘汰策略，默认："LRU"（最近最少使用） */
    public String evictionPolicy = "LRU";

    public CacheConfig() {
    }
}