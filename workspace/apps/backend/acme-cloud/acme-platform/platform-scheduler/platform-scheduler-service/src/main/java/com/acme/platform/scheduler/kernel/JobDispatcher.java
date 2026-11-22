package com.acme.platform.scheduler.kernel;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.core.Clock;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.trace.support.Traces;
import com.acme.platform.scheduler.api.DispatchAcceptView;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.api.ExecutorStatus;
import com.acme.platform.scheduler.api.JobDispatchCommand;
import com.acme.platform.scheduler.api.ShardContext;
import com.acme.platform.scheduler.config.SchedulerProperties;
import com.acme.platform.scheduler.domain.SchExecutorRegistry;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.acme.platform.scheduler.domain.SchJobExecution;
import com.acme.platform.scheduler.infrastructure.repository.ExecutorRegistryRepository;
import com.acme.platform.scheduler.infrastructure.repository.JobDefinitionRepository;
import com.acme.platform.scheduler.infrastructure.repository.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;

/**
 * 指令下发器（REQ-20261121-071002 确认⑤；设计规格 §4 指令通道终稿）：
 * Nacos 服务发现寻址 {@code lb://{app_name}} POST {@code /internal/v1/job/executions/{executionId}/run}，
 * body = JobDispatchCommand（JOB-2 执行器 SDK 对接契约，字段名/路径不得变更）。
 * 实例解析经 LoadBalancerClient.choose(app_name) 直解（选型理由见 SchedulerDispatchClientConfig 类注）；
 * 无实例/无在线执行器 → NO_EXECUTOR。
 * 响应语义：HTTP 200 + accepted=true = 接单（DISPATCHED→RUNNING）；否则按失败口径
 * （NO_EXECUTOR / DISPATCH_TIMEOUT / DISPATCH_REJECTED / DISPATCH_FAILED）置 FAILED 并走重试链。
 * 服务间鉴权：scheduler 签发内部令牌（X-Acme-Token，sub=platform-scheduler 服务主体，
 * 对齐 framework-feign InternalTokenRequestInterceptor 签发惯例）。
 * 并发安全：下发前 CAS PENDING→DISPATCHED（乐观锁承载，0 行更新=他人已推进即让位），
 * 首发直发与 PENDING 恢复扫描双路径不重复下发。
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    /** 下发端点路径（执行器侧承接；JOB-2 契约锚点）。 */
    public static final String EXECUTOR_RUN_PATH = "/internal/v1/job/executions/{executionId}/run";

    /** 下发主体（服务身份；执行器侧 InternalTokenAuthFilter 校验非空 userId）。 */
    private static final String DISPATCH_SUBJECT = "platform-scheduler";

    private final RestClient dispatchRestClient;
    private final InternalTokenIssuer internalTokenIssuer;
    private final org.springframework.cloud.client.loadbalancer.LoadBalancerClient loadBalancerClient;
    private final JobDefinitionRepository definitionRepository;
    private final JobExecutionRepository executionRepository;
    private final ExecutorRegistryRepository executorRegistryRepository;
    private final RetryChain retryChain;
    private final SchedulerAlertNotifier alertNotifier;
    private final SchedulerProperties properties;
    private final Clock clock;

    public JobDispatcher(RestClient schedulerDispatchRestClient,
                         InternalTokenIssuer internalTokenIssuer,
                         org.springframework.cloud.client.loadbalancer.LoadBalancerClient loadBalancerClient,
                         JobDefinitionRepository definitionRepository,
                         JobExecutionRepository executionRepository,
                         ExecutorRegistryRepository executorRegistryRepository,
                         RetryChain retryChain,
                         SchedulerAlertNotifier alertNotifier,
                         SchedulerProperties schedulerProperties,
                         Clock schedulerClock) {
        this.dispatchRestClient = schedulerDispatchRestClient;
        this.internalTokenIssuer = internalTokenIssuer;
        this.loadBalancerClient = loadBalancerClient;
        this.definitionRepository = definitionRepository;
        this.executionRepository = executionRepository;
        this.executorRegistryRepository = executorRegistryRepository;
        this.retryChain = retryChain;
        this.alertNotifier = alertNotifier;
        this.properties = schedulerProperties;
        this.clock = schedulerClock;
    }

    /**
     * 下发单条执行（幂等 CAS 前置；运行于 schedulerDispatchExecutor 线程——IO 密集独立于 tick）。
     */
    public void dispatch(Long executionId) {
        SchJobExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getState() != ExecutionState.PENDING) {
            log.debug("跳过下发：执行不存在或已离开 PENDING，executionId={}", executionId);
            return;
        }
        SchJobDefinition definition = definitionRepository.findById(execution.getDefinitionId()).orElse(null);
        if (definition == null) {
            failExecution(execution, DispatchErrors.DISPATCH_FAILED, "任务定义已不存在");
            return;
        }
        if (!casToDispatched(execution)) {
            // 双触发竞争：另一方已推进（恢复扫描与首发并发的极窄窗口），让位
            log.debug("下发让位：executionId={} 已被并发推进", executionId);
            return;
        }
        doDispatch(definition, execution);
    }

    /**
     * 实际 HTTP 下发（接单确认→RUNNING；失败口径→FAILED+重试链）。
     */
    void doDispatch(SchJobDefinition definition, SchJobExecution dispatched) {
        Instant now = clock.now();
        List<SchExecutorRegistry> executors =
                executorRegistryRepository.findByAppName(definition.getAppName()).stream()
                        .filter(candidate -> candidate.getStatus() == ExecutorStatus.ONLINE)
                        .filter(candidate -> candidate.getHeartbeatAt() != null && candidate.getHeartbeatAt()
                                .isAfter(now.minusSeconds(properties.getExecutor().getHeartbeatStaleSeconds())))
                        .toList();
        if (executors.isEmpty()) {
            failExecution(dispatched, DispatchErrors.NO_EXECUTOR,
                    "无在线执行器: app_name=" + definition.getAppName());
            return;
        }
        // LB 直解实例（DiscoveryClient/Nacos；无实例 → NO_EXECUTOR 走重试链）
        org.springframework.cloud.client.ServiceInstance instance =
                loadBalancerClient.choose(definition.getAppName());
        if (instance == null) {
            failExecution(dispatched, DispatchErrors.NO_EXECUTOR,
                    "服务发现无可用实例: app_name=" + definition.getAppName());
            return;
        }
        JobDispatchCommand command = new JobDispatchCommand(
                definition.getJobKey(), definition.getHandler(), definition.getParam(),
                shardContext(definition, dispatched), dispatched.getFireTime(), resolveTraceId(dispatched),
                dispatched.getAttempt() == null ? 0 : dispatched.getAttempt());
        DispatchAcceptView accepted;
        try {
            accepted = dispatchRestClient.post()
                    .uri("http://" + instance.getHost() + ":" + instance.getPort() + EXECUTOR_RUN_PATH,
                            dispatched.getId())
                    .header(ServiceHeaders.SERVICE_TOKEN,
                            internalTokenIssuer.issue(new AuthenticatedSubject(DISPATCH_SUBJECT, null, null, null)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .body(DispatchAcceptView.class);
        } catch (RestClientResponseException ex) {
            failExecution(dispatched, DispatchErrors.DISPATCH_FAILED,
                    "执行器响应非 2xx: status=" + ex.getStatusCode().value());
            return;
        } catch (RuntimeException ex) {
            // 超时（读超时=接单超时）与其余连接类失败三态分离（见 DispatchErrors）；统一走重试链
            String kind = isTimeout(ex) ? DispatchErrors.DISPATCH_TIMEOUT : DispatchErrors.DISPATCH_FAILED;
            failExecution(dispatched, kind,
                    (isTimeout(ex) ? "下发接单超时" : "下发连接失败") + ": " + ex.getMessage());
            return;
        }
        if (accepted != null && accepted.accepted()) {
            markRunning(dispatched);
            log.info("下发接单：executionId={}, jobKey={}, attempt={}",
                    dispatched.getId(), definition.getJobKey(), command.attempt());
            return;
        }
        failExecution(dispatched, DispatchErrors.DISPATCH_REJECTED,
                "执行器拒绝接单: app_name=" + definition.getAppName());
    }

    /** 超时判定（原因链扫描：读超时/请求超时 → DISPATCH_TIMEOUT，其余连接异常 → DISPATCH_FAILED）。 */
    private static boolean isTimeout(Throwable ex) {
        for (Throwable cursor = ex; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof java.net.SocketTimeoutException
                    || cursor instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 分片上下文（index=execution.shard_index（单分片恒 0），total=shard_total）。 */
    private ShardContext shardContext(SchJobDefinition definition, SchJobExecution execution) {
        int total = definition.getShardTotal() == null || definition.getShardTotal() < 1
                ? 1 : definition.getShardTotal();
        int index = execution.getShardIndex() == null ? 0 : execution.getShardIndex();
        return new ShardContext(index, total);
    }

    /** traceId：落库值优先；缺失（历史数据）即时生成。 */
    private String resolveTraceId(SchJobExecution execution) {
        if (execution.getTraceId() != null && !execution.getTraceId().isBlank()) {
            return execution.getTraceId();
        }
        return Traces.current() != null ? Traces.current().traceId() : Traces.newTraceId();
    }

    /** CAS 推进 DISPATCHED→RUNNING（接单确认；并发竞态/重试仅告警——状态不回退，超时巡检兜底）。 */
    private void markRunning(SchJobExecution dispatched) {
        dispatched.setState(ExecutionState.RUNNING);
        try {
            executionRepository.save(dispatched);
        } catch (RuntimeException ex) {
            log.warn("接单确认推进 RUNNING 失败（保留在途态由巡检兜底）：executionId={}, error={}",
                    dispatched.getId(), ex.getMessage());
        }
    }

    /**
     * 失败口径落库 + 重试链（确认⑤：各失败统一 RETRY execution attempt+1，耗尽→终态 FAILED）；
     * 三态终态失败（NO_EXECUTOR / DISPATCH_FAILED / DISPATCH_TIMEOUT）另发调度告警
     * （REQ-20261122-150756 确认④触发点二；DISPATCH_REJECTED 不在告警口径内）。
     */
    void failExecution(SchJobExecution execution, String errorKind, String detail) {
        execution.setState(ExecutionState.FAILED);
        execution.setError(errorKind + (detail == null ? "" : (": " + detail)));
        executionRepository.save(execution);
        SchJobDefinition definition = definitionRepository.findById(execution.getDefinitionId()).orElse(null);
        if (definition != null) {
            retryChain.spawnRetry(definition, execution);
        }
        if (isAlertableDispatchFailure(errorKind)) {
            alertNotifier.alert(execution.getJobKey(), errorKind, execution.getId(),
                    execution.getAttempt(), execution.getError());
        }
        log.warn("下发失败：executionId={}, jobKey={}, error={}, attempt={}",
                execution.getId(), execution.getJobKey(), execution.getError(), execution.getAttempt());
    }

    /** dispatch 三态终态失败判定（告警口径，确认④钉死三态枚举）。 */
    private static boolean isAlertableDispatchFailure(String errorKind) {
        return DispatchErrors.NO_EXECUTOR.equals(errorKind)
                || DispatchErrors.DISPATCH_FAILED.equals(errorKind)
                || DispatchErrors.DISPATCH_TIMEOUT.equals(errorKind);
    }

    /** PENDING→DISPATCHED CAS（乐观锁承载：他人已推进时 save 抛 STALE_WRITE=让位）。 */
    private boolean casToDispatched(SchJobExecution execution) {
        execution.setState(ExecutionState.DISPATCHED);
        try {
            executionRepository.save(execution);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
