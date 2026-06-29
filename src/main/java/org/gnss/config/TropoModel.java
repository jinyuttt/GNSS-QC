package org.gnss.config;

/**
 * 对流层延迟模型枚举
 */
public enum TropoModel {
    /** Saastamoinen经验模型 */
    SAAS,
    /** 实时估计天顶对流层延迟（ZTD） */
    EST,
    /** 同时估计ZTD和水平梯度参数 */
    ESTG
}