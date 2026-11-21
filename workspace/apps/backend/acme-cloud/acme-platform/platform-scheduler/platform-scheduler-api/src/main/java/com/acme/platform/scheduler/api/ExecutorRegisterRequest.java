package com.acme.platform.scheduler.api;

import java.util.List;

/**
 * 执行器注册请求（设计规格 §5 managed 模式：启动收集后 POST
 * /internal/v1/scheduler/executors/register；uk(app_name, instance_id) 幂等 upsert）。
 * JOB-2 执行器 SDK 对接契约，字段名不得变更。
 *
 * @param appName    执行器所属应用（Nacos 服务名，下发寻址 lb://{app_name}）
 * @param instanceId 实例标识（进程内唯一，重启生成新值）
 * @param host       主机地址
 * @param port       服务端口（下发按 Nacos 服务发现寻址，port 供运维面展示/直连兜底）
 * @param handlers   本实例注册的 handler 清单（{@code @JobHandler} 值）
 */
public record ExecutorRegisterRequest(String appName, String instanceId, String host, Integer port,
                                      List<String> handlers) {
}
