package com.acme.platform.scheduler.api;

/**
 * 执行器在线状态（契约确认④：心跳 stale 阈值 30s，超时视为 OFFLINE）。
 */
public enum ExecutorStatus {

    /** 在线（心跳新鲜）。 */
    ONLINE,

    /** 离线（心跳超时 / 注册前默认）。 */
    OFFLINE
}
