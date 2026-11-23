package com.acme.framework.workflow.support;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.contract.TraceConstants;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.workflow.config.WorkflowServiceTaskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * 引擎 internal callback 自动回调客户端（REQ-20261122-233425 确认②②）：handler 处理完成后
 * POST {workflow 寻址}/internal/v1/workflow/service-tasks/{executionId}/callback（fixed internal 面），
 * body=ServiceTaskCallbackPayload（success=true + handler 结果变量）。
 *
 * <p>鉴权=X-Acme-Token 内部令牌，sub=业务服务名（{@code acme.workflow.service-task.service-name}
 * 缺省取 spring.application.name，Q-D08 服务主体口径）；寻址={@code callback-base-url} 显式直连
 * （单体形态 http://127.0.0.1:{port}——Environment 发送期原始读取+解析，RANDOM_PORT 可用；
 * bean 值/@Value/@ConfigurationProperties 链均会烘焙占位符，禁用——见 resolveBase）否则
 * LoadBalancerClient 按 {@code workflow-service-name}（缺省 platform-workflow）选择（Nacos lb）。</p>
 *
 * <p>失败抛 {@link WorkflowServiceTaskError#CALLBACK_FAILED}（500）——接收端点整体失败交回
 * 引擎委托语义（DelegationException → 重试/挂起），不在 SDK 侧静默。</p>
 */
public class WorkflowServiceTaskCallbackClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowServiceTaskCallbackClient.class);

    /** 引擎回调端点（fixed internal 面；executionId 路径锚点）。 */
    static final String CALLBACK_PATH = "/internal/v1/workflow/service-tasks/%d/callback";

    private static final String DEFAULT_WORKFLOW_SERVICE = "platform-workflow";

    private final RestClient restClient;
    private final com.acme.framework.security.support.InternalTokenIssuer tokenIssuer;
    private final WorkflowServiceTaskProperties properties;
    private final LoadBalancerClient loadBalancerClient;
    private final Environment environment;

    public WorkflowServiceTaskCallbackClient(
            com.acme.framework.security.support.InternalTokenIssuer tokenIssuer,
            WorkflowServiceTaskProperties properties, LoadBalancerClient loadBalancerClient,
            Environment environment) {
        this.restClient = RestClient.create();
        this.tokenIssuer = tokenIssuer;
        this.properties = properties;
        this.loadBalancerClient = loadBalancerClient;
        this.environment = environment;
    }

    /** 自动回调（handler 结果变量并入实例变量后推进；失败抛 CALLBACK_FAILED）。 */
    public void callback(ServiceTaskDispatchPayload payload, Map<String, Object> result) {
        URI target = URI.create(resolveBase() + CALLBACK_PATH.formatted(payload.executionId()));
        try {
            restClient.post()
                    .uri(target)
                    .header(ServiceHeaders.SERVICE_TOKEN, tokenIssuer.issue(new AuthenticatedSubject(
                            serviceName(), payload.tenantId(), null, Set.of())))
                    .header(TraceConstants.TRACE_ID_HEADER, payload.traceId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ServiceTaskCallbackPayload(Boolean.TRUE, result, null))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("ServiceTask 委托回调完成：executionId={} target={} nodeId={}",
                    payload.executionId(), target, payload.nodeId());
        } catch (RuntimeException ex) {
            throw new BizException(WorkflowServiceTaskError.CALLBACK_FAILED,
                    "ServiceTask 回调引擎失败：" + target + " - " + ex.getMessage(), ex);
        }
    }

    /**
     * workflow 寻址（显式直连优先；否则 LoadBalancer 按服务名，无实例显式失败）。
     *
     * <p>占位符时序（TL round-2 diagnostic）：直连 base 优先经 {@code Environment.getProperty}
     * 取原始串再发送期 {@code resolvePlaceholders}——{@code @ConfigurationProperties} 绑定期早于
     * Web 启动，{@code ${local.server.port:${server.port}}} 在绑定期已烘焙为字面 {@code :0}，
     * properties bean 值不含可解析占位符，仅作 Environment 原始读取缺席时的回退
     * （LeavePilotProcessDeployer 部署期惰性读取同款口径）。</p>
     */
    private String resolveBase() {
        String configured = environment.getProperty("acme.workflow.service-task.callback-base-url");
        if (configured != null && !configured.isBlank()) {
            return environment.resolvePlaceholders(configured.trim());
        }
        String fromProperties = properties.getCallbackBaseUrl();
        if (fromProperties != null && !fromProperties.isBlank()) {
            return environment.resolvePlaceholders(fromProperties.trim());
        }
        if (loadBalancerClient == null) {
            throw new BizException(WorkflowServiceTaskError.CALLBACK_FAILED,
                    "回调寻址不可用：未配置 acme.workflow.service-task.callback-base-url 且无 LoadBalancer");
        }
        String service = workflowServiceName();
        ServiceInstance instance = loadBalancerClient.choose(service);
        if (instance == null) {
            throw new BizException(WorkflowServiceTaskError.CALLBACK_FAILED,
                    "workflow 服务无可用实例：" + service);
        }
        return instance.isSecure()
                ? "https://" + instance.getHost() + ":" + instance.getPort()
                : "http://" + instance.getHost() + ":" + instance.getPort();
    }

    private String workflowServiceName() {
        String configured = properties.getWorkflowServiceName();
        return configured == null || configured.isBlank() ? DEFAULT_WORKFLOW_SERVICE : configured.trim();
    }

    /** 回调主体（业务服务名；显式配置优先，缺省 spring.application.name）。 */
    private String serviceName() {
        String configured = properties.getServiceName();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String appName = environment.getProperty("spring.application.name");
        return appName == null || appName.isBlank() ? "application" : appName;
    }
}
