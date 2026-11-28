package com.acme.platform.scheduler.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.scheduler.api.OneShotJobRegisterRequest;
import com.acme.platform.scheduler.application.JobUseCase;
import com.acme.platform.scheduler.application.JobView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部一次性任务注册/注销接口（设计规格 §7 BPM 联动协议；契约确认④：POST /internal/v1/scheduler/jobs，
 * trigger=ONCE@onceFireAt，job_key 唯一，注册即返回，供 BPM-6 定时事件开通位；不经网关对外）。
 * 撞既有 job_key → SCHEDULER_JOB_KEY_DUPLICATE 错误信封（409）。
 * 注销 DELETE /internal/v1/scheduler/jobs/{jobKey}（REQ-20261127-235505 已交付）。
 */
@RestController
public class InternalJobRegistrationController {

    private final JobUseCase jobUseCase;

    public InternalJobRegistrationController(JobUseCase jobUseCase) {
        this.jobUseCase = jobUseCase;
    }

    /** 一次性任务注册（创建即 ENABLED，next_fire=onceFireAt；AC-12 BPM 开通位实证）。 */
    @PostMapping("/internal/v1/scheduler/jobs")
    public Result<JobView> register(@RequestBody OneShotJobRegisterRequest request) {
        return Result.ok(JobUseCase.viewOf(jobUseCase.registerOneShot(request)));
    }

    /**
     * 一次性任务注销（REQ-20261127-235505 P1-01；Feign 契约 {@code SchedulerJobClient#deleteByJobKey}
     * 交付）。幂等：jobKey 不存在为 no-op 成功（不抛 NOT_FOUND、无新增错误码）；存在则复用
     * {@code JobUseCase.delete(Long)} 同款软删语义（时间轮移除 + 软删，执行历史保留），不限制任务类型。
     */
    @DeleteMapping("/internal/v1/scheduler/jobs/{jobKey}")
    public Result<Void> unregister(@PathVariable("jobKey") String jobKey) {
        jobUseCase.deleteByJobKey(jobKey);
        return Result.ok(null);
    }
}
