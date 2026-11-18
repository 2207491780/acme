package com.acme.platform.infra.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.infra.application.JobExecutionView;
import com.acme.platform.infra.application.JobView;
import com.acme.platform.infra.application.JobUseCase;
import com.acme.platform.infra.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务调度 B 端接口（REQ-20261114-071607 §API；权限点 infra:job:*，接口级权限生效）：
 * 注册表查询（key/description/cron/enabled + 最近一次执行摘要）与手动触发
 * （ShedLock 互斥：执行中 JOB_ALREADY_RUNNING；未知 jobKey JOB_NOT_FOUND；成功返回执行记录 id）。
 * REQ-20261117-061017 §API：执行记录分页查询（started_at 倒序，权限点 infra:job:list）。
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

    /** 手动触发（AC-13/AC-14；返回执行记录 id；查询动作纳入审计）。 */
    @PostMapping("/{jobKey}/run")
    @RequirePermission(SystemPermissions.INFRA_JOB_RUN)
    @AuditLog(action = "infra.job.run", targetType = "job", contentParams = {"jobKey"})
    public Long run(@PathVariable String jobKey) {
        return jobUseCase.run(jobKey);
    }

    /** 执行记录分页查询（REQ-20261117-061017 §API；started_at 倒序）。 */
    @GetMapping("/{jobKey}/executions")
    @RequirePermission(SystemPermissions.INFRA_JOB_LIST)
    public PageResult<JobExecutionView> executions(@PathVariable String jobKey,
                                                   @RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer size) {
        return jobUseCase.listExecutions(jobKey, new PageQuery(page, size));
    }
}
