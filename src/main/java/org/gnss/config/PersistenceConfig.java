package org.gnss.config;

/**
 * 持久化配置
 * <p>控制历史数据存储/恢复行为，回调接口由调用方实现</p>
 */
public class PersistenceConfig {

    /** 是否启用持久化，默认：false */
    public boolean enablePersistence = false;

    /** 是否异步存储（不阻塞主清洗流程），默认：true */
    public boolean persistenceAsync = true;

    /** 批量写入大小，默认：100 */
    public int persistenceBatchSize = 100;

    /** 状态快照间隔（秒），默认：600 */
    public int stateSnapshotInterval = 600;

    /** 启动时是否从持久化存储恢复设备状态，默认：true */
    public boolean recoveryOnStartup = true;

    public PersistenceConfig() {
    }
}