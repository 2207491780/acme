package com.acme.platform.scheduler.kernel;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.core.Clock;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度告警发送器（REQ-20261122-150756 确认④）：三类触发点（重试链耗尽 / dispatch 三态终态失败
 * NO_EXECUTOR|DISPATCH_FAILED|DISPATCH_TIMEOUT / 巡检 TIMEOUT）经 platform-system 内部端点
 * POST /internal/v1/notify/messages 扇出全员站内信（复用通知中心既有管道）。
 * <ul>
 *   <li>调用口径照 {@link JobDispatcher}：LoadBalancerClient 直解 {@code lb://platform-system}
 *       + 内部令牌（X-Acme-Token，sub=platform-scheduler 服务主体）；</li>
 *   <li>去重=同 jobKey+reason 5 分钟内存窗（进程内 ConcurrentHashMap；重启即清零可接受——
 *       告警非关键路径）；</li>
 *   <li>发送失败仅 log.warn 降级，绝不放大为调度故障（告警是旁路）。</li>
 * </ul>
 */
@Component
public class SchedulerAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SchedulerAlertNotifier.class);

    /** platform-system 内部告警端点（契约面：BPM-6 将复用，防漂移）。 */
    public static final String NOTIFY_MESSAGES_PATH = "/internal/v1/notify/messages";

    /** 告警服务主体（sender；契约钉死示例值）。 */
    public static final String ALERT_SENDER = "platform-scheduler";

    /** 下发目标服务名（Nacos）。 */
    private static final String NOTIFY_SERVICE_NAME = "platform-system";

    /** 触发点原因：重试链耗尽（FAILED 终态，attempt 达 maxAttempts）。 */
    public static final String REASON_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

    /** 触发点原因：巡检 TIMEOUT（RUNNING 超 timeout_ms）。 */
    public static final String REASON_EXECUTION_TIMEOUT = DispatchErrors.EXECUTION_TIMEOUT;

    /** 同 jobKey+reason 去重窗口（确认④：5 分钟内存窗）。 */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    private final RestClient dispatchRestClient;
    private final InternalTokenIssuer internalTokenIssuer;
    private final LoadBalancerClient loadBalancerClient;
    private final Clock clock;

    /** 去重窗：key=jobKey|reason → 窗内上次发送时刻（compute 原子判定+登记）。 */
    private final ConcurrentHashMap<String, Instant> dedupWindow = new ConcurrentHashMap<>();

    public SchedulerAlertNotifier(RestClient schedulerDispatchRestClient,
                                  InternalTokenIssuer internalTokenIssuer,
                                  LoadBalancerClient loadBalancerClient,
                                  Clock schedulerClock) {
        this.dispatchRestClient = schedulerDispatchRestClient;
        this.internalTokenIssuer = internalTokenIssuer;
        this.loadBalancerClient = loadBalancerClient;
        this.clock = schedulerClock;
    }

    /**
     * 发送调度告警（去重窗命中即静默跳过；失败仅告警日志）。
     *
     * @param jobKey      任务键（title 必含）
     * @param reason      触发原因（title 必含；RETRY_EXHAUSTED / NO_EXECUTOR / DISPATCH_FAILED /
     *                    DISPATCH_TIMEOUT / EXECUTION_TIMEOUT）
     * @param executionId 源 execution id（content 必含，可追溯）
     * @param attempt     当前轮次（content 必含）
     * @param error       错误详情（content 必含，可空）
     */
    public void alert(String jobKey, String reason, Long executionId, Integer attempt, String error) {
        Instant now = clock.now();
        String dedupKey = jobKey + "|" + reason;
        boolean[] shouldSend = {false};
        dedupWindow.compute(dedupKey, (key, lastSentAt) -> {
            if (lastSentAt != null && lastSentAt.plus(DEDUP_WINDOW).isAfter(now)) {
                return lastSentAt;
            }
            shouldSend[0] = true;
            return now;
        });
        if (!shouldSend[0]) {
            log.debug("告警去重命中（5 分钟窗）：jobKey={}, reason={}", jobKey, reason);
            return;
        }
        send(jobKey, reason, executionId, attempt, error);
    }

    private void send(String jobKey, String reason, Long executionId, Integer attempt, String error) {
        try {
            ServiceInstance instance = loadBalancerClient.choose(NOTIFY_SERVICE_NAME);
            if (instance == null) {
                log.warn("调度告警降级（服务发现无 platform-system 实例）：jobKey={}, reason={}", jobKey, reason);
                return;
            }
            String title = "调度任务告警: " + jobKey + " [" + reason + "]";
            String content = "executionId=" + executionId
                    + ", attempt=" + attempt
                    + ", error=" + (error == null ? "" : error);
            dispatchRestClient.post()
                    .uri("http://" + instance.getHost() + ":" + instance.getPort() + NOTIFY_MESSAGES_PATH)
                    .header(ServiceHeaders.SERVICE_TOKEN,
                            internalTokenIssuer.issue(new AuthenticatedSubject(ALERT_SENDER, null, null, null)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NotifyMessageBody(ALERT_SENDER, title, content))
                    .retrieve()
                    .toBodilessEntity();
            log.info("调度告警已发送：jobKey={}, reason={}, executionId={}", jobKey, reason, executionId);
        } catch (RuntimeException ex) {
            log.warn("调度告警发送失败（旁路降级不放大故障）：jobKey={}, reason={}, error={}",
                    jobKey, reason, ex.getMessage());
        }
    }

    /** 通知端点 body 镜像（契约 wire 形态 {sender,title,content?}；字段名不得变更）。 */
    record NotifyMessageBody(String sender, String title, String content) {
    }
}
