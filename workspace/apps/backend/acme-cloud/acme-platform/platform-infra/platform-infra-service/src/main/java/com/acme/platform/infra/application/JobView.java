package com.acme.platform.infra.application;

import java.time.Instant;

/**
 * 任务注册表视图（REQ-20261114-071607 §API：key/description/cron/enabled + 最近一次执行摘要）。
 */
public record JobView(String key, String description, String cron, Boolean enabled,
                      LastExecution lastExecution) {

    /** 最近一次执行摘要（无执行历史时为 null）。 */
    public record LastExecution(String status, Instant startedAt, Instant finishedAt, String error) {
    }
}
