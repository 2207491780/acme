package com.acme.platform.workflow.api.dto;

import java.util.Map;

/**
 * ServiceTask 委托下发协议体（BPM → 业务服务 POST {serviceRef}{callbackPath}；设计规格 §7
 * 集成模型，BPM-1 钉死字段名——BPM-5 SDK 消费侧解码契约，字段名不得变更）。
 *
 * @param executionId workflow 执行 id（回调 POST /internal/v1/workflow/service-tasks/{executionId}/callback
 *                    的路径锚点）
 * @param instanceId  流程实例 id
 * @param businessKey 业务键（透传，便于业务侧幂等/对账）
 * @param tenantId    租户 id（业务侧上下文回放）
 * @param nodeId      ServiceTask 节点 id（BPMN process 内唯一）
 * @param serviceRef  目标服务名（Nacos lb 寻址键；桩端点场景为 http(s) 直连 URL）
 * @param callbackPath 回调登记路径（workflow internal callback 端点，业务侧原样回传使用）
 * @param variables   变量快照（下发时刻实例变量只读拷贝）
 * @param traceId     链路追踪 id（framework-trace 生成，下发/回调日志对账）
 * @param attempt     下发轮次（0=首发，重试逐次 +1）
 */
public record ServiceTaskDispatchPayload(Long executionId, Long instanceId, String businessKey,
                                         String tenantId, String nodeId, String serviceRef,
                                         String callbackPath, Map<String, Object> variables,
                                         String traceId, int attempt) {
}
