package com.acme.platform.workflow.infrastructure.client;

import com.acme.common.contract.ServiceHeaders;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.platform.workflow.api.dto.ServiceTaskDispatchPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Map;

/**
 * ServiceTask HTTP 委托下发客户端（确认⑤/设计规格 §7 集成模型；BPM-1 钉协议见交付报告
 * 「委托协议终稿」）：POST {serviceRef 解析}/{callbackPath}，body=ServiceTaskDispatchPayload，
 * 鉴权=workflow 签发内部令牌（X-Acme-Token，sub=platform-workflow 服务主体，与调度中心下发
 * 同款惯例）；寻址=serviceRef 含 scheme 直连（内嵌桩端点）/ 否则 LoadBalancerClient.choose
 * （Nacos lb，SchedulerDispatchClientConfig 同款选型）。下发失败抛 DelegationException 交
 * 行为层走重试/挂起语义。
 */
@Component
public class ServiceTaskDelegateClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceTaskDelegateClient.class);

    /** 下发主体（服务身份；目标服务 InternalTokenAuthFilter 校验非空 userId）。 */
    private static final String DELEGATION_SUBJECT = "platform-workflow";

    private final RestClient delegateRestClient;
    private final InternalTokenIssuer internalTokenIssuer;
    private final LoadBalancerClient loadBalancerClient;

    public ServiceTaskDelegateClient(RestClient workflowDelegateRestClient,
                                     InternalTokenIssuer internalTokenIssuer,
                                     LoadBalancerClient loadBalancerClient) {
        this.delegateRestClient = workflowDelegateRestClient;
        this.internalTokenIssuer = internalTokenIssuer;
        this.loadBalancerClient = loadBalancerClient;
    }

    /**
     * 下发委托（同步阻塞；失败抛 {@link DelegationException}——行为层按 acme:retryMaxAttempts
     * 重试后挂起实例 SERVICE_TASK_FAILED）。
     */
    public void dispatch(ServiceTaskDispatchPayload payload, String callbackPath, long timeoutMs) {
        URI target = resolveTarget(payload.serviceRef(), callbackPath);
        try {
            delegateRestClient.post()
                    .uri(target)
                    .header(ServiceHeaders.SERVICE_TOKEN, internalTokenIssuer.issue(
                            new com.acme.framework.core.support.AuthenticatedSubject(
                                    DELEGATION_SUBJECT, payload.tenantId(), null, java.util.Set.of())))
                    .header(com.acme.common.contract.TraceConstants.TRACE_ID_HEADER, payload.traceId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("ServiceTask 委托下发完成：executionId={} target={} attempt={}",
                    payload.executionId(), target, payload.attempt());
        } catch (RestClientResponseException ex) {
            throw new DelegationException("委托端点响应失败：" + ex.getStatusCode(), ex);
        } catch (RuntimeException ex) {
            throw new DelegationException("委托下发失败：" + ex.getMessage(), ex);
        }
    }

    /** 目标解析（http(s) 直连桩端点；Nacos 服务名经 LB 直解——无实例即失败）。 */
    private URI resolveTarget(String serviceRef, String callbackPath) {
        String lower = serviceRef.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return URI.create(serviceRef + callbackPath);
        }
        ServiceInstance instance = loadBalancerClient.choose(serviceRef);
        if (instance == null) {
            throw new DelegationException("无可用实例：" + serviceRef, null);
        }
        String base = instance.isSecure()
                ? "https://" + instance.getHost() + ":" + instance.getPort()
                : "http://" + instance.getHost() + ":" + instance.getPort();
        return URI.create(base + callbackPath);
    }

    /** 委托下发失败（行为层重试/挂起语义锚点）。 */
    public static final class DelegationException extends RuntimeException {

        public DelegationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
