package com.acme.platform.scheduler.application;

import java.time.Instant;
import java.util.List;

/**
 * 执行器视图（管理面 /admin/v1/scheduler/executors；AC-10：注册入库 / 心跳刷新 / 失联 OFFLINE）。
 *
 * @param id          注册行 id（wire 字符串化，全局 Long→String）
 * @param appName     执行器所属应用（Nacos 服务名）
 * @param instanceId  实例标识
 * @param host        主机地址
 * @param port        服务端口（可空）
 * @param handlers    handler 清单
 * @param heartbeatAt 最近心跳时刻
 * @param status      在线状态（心跳 stale 阈值 30s 动态判定 + 失联回写双口径）
 */
public record ExecutorView(String id, String appName, String instanceId, String host, Integer port,
                           List<String> handlers, Instant heartbeatAt, String status) {
}
