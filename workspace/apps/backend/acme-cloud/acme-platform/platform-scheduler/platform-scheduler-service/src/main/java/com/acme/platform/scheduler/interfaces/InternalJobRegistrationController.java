package com.acme.platform.scheduler.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.scheduler.api.OneShotJobRegisterRequest;
import com.acme.platform.scheduler.application.JobUseCase;
import com.acme.platform.scheduler.application.JobView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部一次性任务注册接口（设计规格 §7 BPM 联动协议；契约确认④：POST /internal/v1/scheduler/jobs，
 * trigger=ONCE@onceFireAt，job_key 唯一，注册即返回，供 BPM-6 定时事件开通位；不经网关对外）。
 * 撞既有 job_key → SCHEDULER_JOB_KEY_DUPLICATE 错误信封（409）。
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
}
