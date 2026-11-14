package com.acme.platform.infra.interfaces;

import com.acme.platform.infra.application.JobView;
import com.acme.platform.infra.application.JobUseCase;
import com.acme.platform.infra.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务调度 B 端接口（REQ-20261114-071607 §API；权限点 infra:job:*，接口级权限生效）：
 * 注册表查询（key/description/cron/enabled + 最近一次执行摘要）与手动触发
 * （ShedLock 互斥：执行中 JOB_ALREADY_RUNNING；未知 jobKey JOB_NOT_FOUND；成功返回执行记录 id）。
 */
@RestController
@RequestMapping("/admin/v1/infra/jobs")
public class JobController {

    private final JobUseCase jobUseCase;

    public JobController(JobUseCase jobUseCase) {
        this.jobUseCase = jobUseCase;
    }

    /** 注册表列表（AC-12）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.INFRA_JOB_LIST)
    public List<JobView> list() {
        return jobUseCase.list();
    }

    /** 手动触发（AC-13/AC-14；返回执行记录 id）。 */
    @PostMapping("/{jobKey}/run")
    @RequirePermission(SystemPermissions.INFRA_JOB_RUN)
    public Long run(@PathVariable String jobKey) {
        return jobUseCase.run(jobKey);
    }
}
