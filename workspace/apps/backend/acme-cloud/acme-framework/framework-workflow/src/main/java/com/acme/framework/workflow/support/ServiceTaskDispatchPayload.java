package com.acme.framework.workflow.support;

import java.util.Map;

/**
 * ServiceTask 委托下发协议体（BPM → 业务服务 POST {serviceRef}{callbackPath} 的消费侧解码形态）。
 *
 * <p>wire 契约事实源=platform-workflow-api {@code ServiceTaskDispatchPayload}（BPM-1 冻结，
 * 注释钉死「BPM-5 SDK 消费侧解码契约，字段名不得变更」）。framework 层禁依赖任何服务模块
 * （4.4.4 enforcer），故本 SDK 按冻结字段名镜像自建解码 record（framework-job 执行器 SDK 自建
 * JobExecutionContext 同款先例）：字段名/语义/顺序逐字对齐，Jackson 按名解码与下发侧序列化互认。
 * 消费侧不得单方变更字段名——对齐由 support 层单测锁定。</p>
 *
 * @param executionId  workflow 执行 id（自动回调 POST /internal/v1/workflow/service-tasks/{executionId}/callback
 *                     的路径锚点）
 * @param instanceId   流程实例 id
 * @param businessKey  业务键（透传，业务侧幂等/对账锚点）
 * @param tenantId     租户 id（业务侧上下文回放）
 * @param nodeId       ServiceTask 节点 id（BPMN process 内唯一）
 * @param serviceRef   目标服务名（handler 注册表路由键；单体直连形态为 http(s) URL——逐字匹配）
 * @param callbackPath 回调登记路径（目标服务承接下发的内部端点，业务侧原样承载）
 * @param variables    变量快照（下发时刻实例变量只读拷贝）
 * @param traceId      链路追踪 id（framework-trace 生成，下发/回调日志对账）
 * @param attempt      下发轮次（0=首发，重试逐次 +1）
 */
public record ServiceTaskDispatchPayload(Long executionId, Long instanceId, String businessKey,
                                         String tenantId, String nodeId, String serviceRef,
                                         String callbackPath, Map<String, Object> variables,
                                         String traceId, int attempt) {
}
