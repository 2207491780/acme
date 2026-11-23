package com.acme.framework.workflow.support;

import java.util.Map;

/**
 * ServiceTask 委托处理 SPI（REQ-20261122-233425 确认②①；业务侧实现 + 容器注册，
 * {@link WorkflowServiceTaskHandlerRegistry} 按 serviceRef 路由）：
 * 引擎下发 ServiceTask → SDK 接收端点按 serviceRef 路由至 handler → handler 落库业务终态
 * （如 HR 备案）→ 返回结果变量（并入实例变量）→ SDK 自动回调引擎 internal callback 推进。
 *
 * <p>上下文口径：下发请求携 X-Acme-Token（sub=platform-workflow），SDK 接收端点经
 * InternalTokenAuthFilter 验签后绑定 UserContext/TenantContext——handler 在租户上下文内执行，
 * tenantId 亦随 payload 显式携带（防御性回放）。</p>
 */
public interface WorkflowServiceTaskHandler {

    /**
     * 注册键（节点 {@code acme:serviceRef} 逐字匹配；分布式=Nacos 服务名，单体直连形态=http(s) URL 逐字）。
     */
    String serviceRef();

    /**
     * 处理委托（同步；业务落库完成后返回结果变量——键值并入实例变量后回调推进）。
     * 特殊键 {@code __complete__=false} 表示业务未完成、等待再次回调（workflow-api 契约，透传）。
     *
     * @param payload 下发协议体（冻结字段名解码形态）
     * @return 结果变量（可空）
     */
    Map<String, Object> handle(ServiceTaskDispatchPayload payload);
}
