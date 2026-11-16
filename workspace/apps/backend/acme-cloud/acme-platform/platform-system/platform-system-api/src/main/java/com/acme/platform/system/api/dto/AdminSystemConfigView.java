package com.acme.platform.system.api.dto;

/**
 * 租户级系统配置视图（REQ-20261116-230354；version 经全局 Long→String 序列化为字符串）。
 * 无配置行租户返回缺省视图 { watermarkEnabled:true, lockIdleTimeoutMs:0, version:"0" }。
 */
public record AdminSystemConfigView(boolean watermarkEnabled, int lockIdleTimeoutMs, Long version) {
}