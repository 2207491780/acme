package com.acme.framework.job.executor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.job.config.JobProperties;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 调度中心出站客户端（确认④⑤）：注册 / 心跳 / 结果上报三端点，寻址经 LoadBalancerClient 直解
 * {@code lb://{scheduler-service-name}}（与 scheduler 下发面 JobDispatcher 同款选型——
 * 服务名动态、无需 @LoadBalanced builder 拦截器 BPP 时序）；
 * 服务间鉴权对齐现有 internal 面：X-Acme-Token 经 framework-security InternalTokenIssuer 签发
 * （sub=本服务 appName，执行器侧接收端校验 userId 非空即可通过）。
 * 注册/心跳/上报失败一律抛出由调用方处置（生命周期降级 / 上报告警），不在本类静默吞错。
 */
public class SchedulerClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClient.class);

    /** 执行器注册端点（JOB-1 契约面：/internal/v1/scheduler/executors/register，幂等 upsert）。 */
    public static final String REGISTER_PATH = "/internal/v1/scheduler/executors/register";

    /** 心跳端点（JOB-1 契约面：/internal/v1/scheduler/executors/heartbeat）。 */
    public static final String HEARTBEAT_PATH = "/internal/v1/scheduler/executors/heartbeat";

    /** 结果上报端点前缀（JOB-1 契约面：/internal/v1/scheduler/executions/{executionId}/result）。 */
    public static final String RESULT_PATH = "/internal/v1/scheduler/executions/";

    /** 连接超时（注册/心跳/上报均为轻请求）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 读超时（调度中心同步处理，轻请求口径）。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final LoadBalancerClient loadBalancer;
    private final InternalTokenIssuer tokenIssuer;
    private final JobProperties properties;
    private final Supplier<String> appNameSupplier;
    private final RestClient restClient;

    public SchedulerClient(LoadBalancerClient loadBalancer, InternalTokenIssuer tokenIssuer,
                           JobProperties properties, Supplier<String> appNameSupplier) {
        this.loadBalancer = loadBalancer;
        this.tokenIssuer = tokenIssuer;
        this.properties = properties;
        this.appNameSupplier = appNameSupplier;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** LB 客户端可用性（缺席=纯本地形态，执行器生命周期直接永久降级）。 */
    public boolean isAddressable() {
        return loadBalancer != null;
    }

    /** 执行器注册（幂等 upsert）。 */
    public void register(String appName, String instanceId, String host, Integer port, List<String> handlers) {
        post(REGISTER_PATH, new RegisterBody(appName, instanceId, host, port, handlers));
    }

    /** 心跳续期（失败由调用方降级，恢复后重注册）。 */
    public void heartbeat(String appName, String instanceId, List<String> handlers) {
        post(HEARTBEAT_PATH, new HeartbeatBody(appName, instanceId, handlers));
    }

    /** 结果上报（SUCCESS/FAILED/TIMEOUT 终态；失败由调用方告警，调度侧 CAS/巡检兜底）。 */
    public void reportResult(long executionId, ResultReport report) {
        post(RESULT_PATH + executionId + "/result", report);
    }

    private void post(String path, Object body) {
        ServiceInstance instance = loadBalancer.choose(properties.getExecutor().getSchedulerServiceName());
        if (instance == null) {
            throw new IllegalStateException("服务发现无可用实例: "
                    + properties.getExecutor().getSchedulerServiceName());
        }
        RestClient.RequestBodySpec spec = restClient.post()
                .uri("http://" + instance.getHost() + ":" + instance.getPort() + path)
                .contentType(MediaType.APPLICATION_JSON);
        if (tokenIssuer != null) {
            String token = tokenIssuer.issue(new AuthenticatedSubject(appNameSupplier.get(), null, null, null));
            spec = spec.header(ServiceHeaders.SERVICE_TOKEN, token);
        }
        spec.body(body).retrieve().toBodilessEntity();
    }

    /** 注册 body 镜像（JOB-1 契约面 ExecutorRegisterRequest 字段名一致）。 */
    record RegisterBody(String appName, String instanceId, String host, Integer port, List<String> handlers) {
    }

    /** 心跳 body 镜像（JOB-1 契约面 ExecutorHeartbeatRequest 字段名一致）。 */
    record HeartbeatBody(String appName, String instanceId, List<String> handlers) {
    }
}
