package com.acme.platform.workflow.infrastructure.client;

import com.acme.common.core.BizException;
import com.acme.framework.feign.support.RemoteCallException;
import com.acme.platform.scheduler.api.OneShotJobRegisterRequest;
import com.acme.platform.scheduler.api.SchedulerErrorCodes;
import com.acme.platform.scheduler.api.SchedulerJobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 调度中心注册客户端（确认⑥：Feign → platform-scheduler internal 注册/注销 API 的薄包装，
 * 统一日志与异常面；重试注册/本地兜底逻辑归 {@link com.acme.platform.workflow.engine.TimerScheduler}）。
 * 注册失败抛 RuntimeException 交上层入重试表；注销失败按告警处理（DELETE /internal/v1/scheduler/jobs/{jobKey}
 * 已随 REQ-20261127-235505 交付——404 收敛为幂等成功按异常携带的 HTTP 状态码判定，不阻断本地清理）。
 */
@Component
public class SchedulerRegistrationClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRegistrationClient.class);

    /** 下发目标应用（设计规格 §6：appName=platform-workflow）。 */
    static final String APP_NAME = "platform-workflow";

    /** 下发 handler 标识（BPM-6 执行器路由键，协议终稿见交付报告「定时联动协议终稿」）。 */
    static final String HANDLER = "workflow-timer-fire";

    private final ObjectProvider<SchedulerJobClient> schedulerJobClients;

    public SchedulerRegistrationClient(ObjectProvider<SchedulerJobClient> schedulerJobClients) {
        this.schedulerJobClients = schedulerJobClients;
    }

    /**
     * 注册一次性任务（jobKey=wf-timer:{timerJobId}，appName=platform-workflow，
     * handler=workflow-timer-fire，param={timerJobId} jsonb 文本；撞既有 jobKey 视为已注册——
     * 调度中心 SCHEDULER_JOB_KEY_DUPLICATE 409 在本客户端内收敛为幂等成功）。
     */
    public void register(String jobKey, Instant onceFireAt, String timerJobId) {
        try {
            schedulerJobClients.getObject().registerOneShot(new OneShotJobRegisterRequest(
                    jobKey, APP_NAME, HANDLER, onceFireAt,
                    "{\"timerJobId\":\"" + timerJobId + "\"}", null, null, null));
            log.debug("一次性任务注册完成：jobKey={} dueAt={}", jobKey, onceFireAt);
        } catch (RuntimeException failure) {
            if (isDuplicateKey(failure)) {
                log.info("一次性任务已注册（幂等收敛）：jobKey={}", jobKey);
                return;
            }
            throw failure;
        }
    }

    /** 注销一次性任务（按 jobKey；404 视为已注销幂等收敛）。 */
    public void unregister(String jobKey) {
        try {
            schedulerJobClients.getObject().deleteByJobKey(jobKey);
        } catch (RuntimeException failure) {
            if (isNotFound(failure)) {
                log.debug("一次性任务不存在（幂等收敛）：jobKey={}", jobKey);
                return;
            }
            throw failure;
        }
    }

    /** 撞键幂等判定（结构化错误码锚点 {@link SchedulerErrorCodes#SCHEDULER_JOB_KEY_DUPLICATE}，禁 message 字符串匹配）。 */
    private static boolean isDuplicateKey(RuntimeException failure) {
        return failure instanceof BizException biz
                && SchedulerErrorCodes.SCHEDULER_JOB_KEY_DUPLICATE.equals(biz.errorCode().code());
    }

    /** 注销 404 幂等判定（仅按异常携带的 HTTP 状态码判定，载体=RemoteCallException，禁 message 字符串匹配）。 */
    private static boolean isNotFound(RuntimeException failure) {
        return failure instanceof RemoteCallException remote && remote.httpStatus() == 404;
    }
}
