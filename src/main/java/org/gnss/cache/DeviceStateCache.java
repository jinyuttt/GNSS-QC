package org.gnss.cache;

import org.gnss.config.CacheConfig;
import org.gnss.config.PersistenceConfig;
import org.gnss.model.DeviceState;
import org.gnss.persistence.PersistenceCallback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class DeviceStateCache {

    private final int maxDevices;
    private final PersistenceConfig persistenceConfig;
    private final PersistenceCallback persistenceCallback;

    private final ConcurrentHashMap<String, DeviceState> deviceStates;
    private final AtomicInteger deviceCount;
    private final ReentrantLock evictionLock;

    public DeviceStateCache(CacheConfig cacheConfig, PersistenceConfig persistenceConfig,
                            PersistenceCallback persistenceCallback) {
        this.maxDevices = cacheConfig.maxDevices;
        this.persistenceConfig = persistenceConfig;
        this.persistenceCallback = persistenceCallback;

        this.deviceStates = new ConcurrentHashMap<>();
        this.deviceCount = new AtomicInteger(0);
        this.evictionLock = new ReentrantLock();
    }

    public DeviceState get(String deviceId) {
        return deviceStates.get(deviceId);
    }

    public DeviceState getOrCreate(String deviceId) {
        DeviceState existing = deviceStates.get(deviceId);
        if (existing != null) {
            return existing;
        }
        return deviceStates.computeIfAbsent(deviceId, id -> {
            evictIfNeeded();
            return new DeviceState();
        });
    }

    public void put(String deviceId, DeviceState state) {
        DeviceState prev = deviceStates.put(deviceId, state);
        if (prev == null) {
            deviceCount.incrementAndGet();
        }
    }

    public void remove(String deviceId) {
        DeviceState prev = deviceStates.remove(deviceId);
        if (prev != null) {
            deviceCount.decrementAndGet();
            if (persistenceConfig.enablePersistence && persistenceCallback != null) {
                persistenceCallback.saveDeviceState(deviceId, prev.toSnapshot(deviceId));
            }
        }
    }

    public void clear() {
        if (persistenceConfig.enablePersistence && persistenceCallback != null) {
            for (Map.Entry<String, DeviceState> entry : deviceStates.entrySet()) {
                persistenceCallback.saveDeviceState(entry.getKey(), entry.getValue().toSnapshot(entry.getKey()));
            }
        }
        deviceStates.clear();
        deviceCount.set(0);
    }

    public int size() {
        return deviceStates.size();
    }

    public boolean contains(String deviceId) {
        return deviceStates.containsKey(deviceId);
    }

    public Map<String, DeviceState> getAllDeviceStates() {
        return new LinkedHashMap<>(deviceStates);
    }

    private void evictIfNeeded() {
        if (deviceStates.size() < maxDevices) {
            return;
        }
        if (!evictionLock.tryLock()) {
            return;
        }
        try {
            if (deviceStates.size() >= maxDevices) {
                String oldestKey = null;
                long oldestTime = Long.MAX_VALUE;
                for (Map.Entry<String, DeviceState> entry : deviceStates.entrySet()) {
                    long updateTime = entry.getValue().getLastUpdateTime();
                    if (updateTime < oldestTime) {
                        oldestTime = updateTime;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) {
                    DeviceState evicted = deviceStates.remove(oldestKey);
                    if (evicted != null && persistenceConfig.enablePersistence && persistenceCallback != null) {
                        persistenceCallback.saveDeviceState(oldestKey, evicted.toSnapshot(oldestKey));
                    }
                }
            }
        } finally {
            evictionLock.unlock();
        }
    }
}