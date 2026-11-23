package com.acme.business.leave.sdk;

import com.acme.business.leave.application.LeaveRequestUseCase;
import com.acme.framework.workflow.support.ServiceTaskDispatchPayload;
import com.acme.framework.workflow.support.WorkflowServiceTaskHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HR 备案 ServiceTask 委托 handler（REQ-20261122-233425 确认⑤⑤；framework-workflow SDK SPI
 * 实现——引擎下发 ServiceTask → SDK 接收端点按 serviceRef 路由至此 → 落库 status=FILED →
 * 返回结果变量（filed=true，经回调并入实例变量——INSTANCE_COMPLETED 终态归因锚点）→
 * SDK 自动回调引擎 internal callback 推进）。
 *
 * <p>注册键（acme:serviceRef 逐字匹配）：分布式=Nacos 服务名 business-leave；单体直连形态
 * 节点 serviceRef 为 http(s) URL——经 {@code acme.leave.service-ref} 承载（聚合部署面按形态
 * 设置，与试点 BPMN 节点属性同源）。占位符在注册时点逐字解析（RANDOM_PORT 形态
 * local.server.port 于 web 服务器启动后才可达——SDK 收集器在 ContextRefreshedEvent 登记）。</p>
 */
@Component("leaveFileServiceTaskHandler")
public class LeaveFileServiceTaskHandler implements WorkflowServiceTaskHandler {

    /** 分布式注册键缺省（单体直连形态经 acme.leave.service-ref 覆盖为 http(s) URL）。 */
    public static final String DEFAULT_SERVICE_REF = "business-leave";

    private final org.springframework.core.env.Environment environment;
    private final LeaveRequestUseCase leaveRequestUseCase;

    public LeaveFileServiceTaskHandler(org.springframework.core.env.Environment environment,
                                       LeaveRequestUseCase leaveRequestUseCase) {
        this.environment = environment;
        this.leaveRequestUseCase = leaveRequestUseCase;
    }

    @Override
    public String serviceRef() {
        // 注册时点解析（收集器在 ContextRefreshedEvent 调用——部署形态占位符此刻可达）
        String configured = environment.getProperty("acme.leave.service-ref", DEFAULT_SERVICE_REF);
        return configured == null || configured.isBlank() ? DEFAULT_SERVICE_REF : configured.trim();
    }

    @Override
    public Map<String, Object> handle(ServiceTaskDispatchPayload payload) {
        // instanceId=可靠锚点（businessKey 为调用方语义键）；备案幂等（重复下发/重试轮次直返）
        return leaveRequestUseCase.fileByServiceTask(payload.instanceId(), payload.businessKey());
    }
}
