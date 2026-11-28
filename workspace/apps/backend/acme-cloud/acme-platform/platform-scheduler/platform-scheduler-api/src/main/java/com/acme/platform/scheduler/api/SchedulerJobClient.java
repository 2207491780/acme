package com.acme.platform.scheduler.api;

import com.acme.common.core.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 服务间一次性任务注册契约（REQ-20261121-142316 确认⑥ BPM 定时联动；对齐 AuthorizationClient
 * 惯例，platform-workflow 跨服务取数仅经本契约）。回参信封用 common-core Result（服务端
 * InternalJobRegistrationController 线上形态；scheduler-api 不引 system-api——4.4.2 跨域仅经
 * 消费方 service 面）。
 *
 * <p>协议终稿（BPM-1 钉死，BPM-6 集成消费）：
 * <ul>
 *   <li>注册 POST /internal/v1/scheduler/jobs（OneShotJobRegisterRequest，撞既有 jobKey →
 *       platform-scheduler-01-0001 错误信封，Feign 错误解码为 BizException）</li>
 *   <li>注销 DELETE /internal/v1/scheduler/jobs/{jobKey}（按 jobKey 注销一次性任务；
 *       REQ-20261127-235505 已交付，jobKey 不存在为 no-op 成功——workflow 侧注销 404 收敛为
 *       幂等成功，不阻断本地清理）</li>
 * </ul>
 * jobKey 约定 = {@code wf-timer:{timerJobId}}（设计规格 §6；BPM 侧 uk(scheduler_job_key)
 * + 状态 CAS 保证注册/触发幂等）。</p>
 */
@FeignClient(name = "platform-scheduler", contextId = "schedulerJobClient")
public interface SchedulerJobClient {

    /** 一次性任务注册（注册即 ENABLED；撞 key → SCHEDULER_JOB_KEY_DUPLICATE 409）。 */
    @PostMapping("/internal/v1/scheduler/jobs")
    Result<OneShotJobRegisteredView> registerOneShot(@RequestBody OneShotJobRegisterRequest request);

    /** 按任务键注销一次性任务（jobKey = wf-timer:{timerJobId}）。 */
    @DeleteMapping("/internal/v1/scheduler/jobs/{jobKey}")
    Result<Void> deleteByJobKey(@PathVariable("jobKey") String jobKey);
}
