package com.acme.platform.scheduler.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.scheduler.api.SchedulerJobStatus;
import com.acme.platform.scheduler.application.JobSaveRequest;
import com.acme.platform.scheduler.application.JobStatusUpdateRequest;
import com.acme.platform.scheduler.application.JobUseCase;
import com.acme.platform.scheduler.application.JobView;
import com.acme.framework.web.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务定义管理 B 端接口（契约确认②④；权限点对齐 platform-system V26 种子）：
 * <ul>
 *   <li>GET    /admin/v1/scheduler/jobs              → scheduler:job:list（73）</li>
 *   <li>POST   /admin/v1/scheduler/jobs              → scheduler:job:create（74；创建默认 DISABLED）</li>
 *   <li>PUT    /admin/v1/scheduler/jobs/{id}         → scheduler:job:update（75；job_key 不可改）</li>
 *   <li>PUT    /admin/v1/scheduler/jobs/{id}/status  → scheduler:job:update（75；启停专用端点）</li>
 *   <li>POST   /admin/v1/scheduler/jobs/{id}/trigger → scheduler:job:update（75；MANUAL execution）</li>
 *   <li>DELETE /admin/v1/scheduler/jobs/{id}         → scheduler:job:delete（76）</li>
 * </ul>
 * 写端点 @AuditLog 全量埋点（REQ-20261117-061017 确认③：含 beforeQuery 旧值快照）；
 * 出参经 framework-web StandardResponseAdvice 统一 Result 信封（FR-003）。
 */
@RestController
@RequestMapping("/admin/v1/scheduler/jobs")
public class AdminJobController {

    private final JobUseCase jobUseCase;

    public AdminJobController(JobUseCase jobUseCase) {
        this.jobUseCase = jobUseCase;
    }

    /** 定义分页列表（AC-2 面；过滤 jobKey/appName/status）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_LIST)
    public PageResult<JobView> list(@RequestParam(required = false) String jobKey,
                                    @RequestParam(required = false) String appName,
                                    @RequestParam(required = false) SchedulerJobStatus status,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        return jobUseCase.list(jobKey, appName, status, new PageQuery(page, size));
    }

    /** 创建（默认 DISABLED；重复 job_key → SCHEDULER_JOB_KEY_DUPLICATE，AC-2）。 */
    @PostMapping
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_CREATE)
    @AuditLog(action = "scheduler.job.create", targetType = "scheduler-job")
    public JobView create(@Valid @RequestBody JobSaveRequest request) {
        return jobUseCase.create(request);
    }

    /** 更新（job_key 变更 → SCHEDULER_JOB_KEY_IMMUTABLE；乐观锁 version，AC-2）。 */
    @PutMapping("/{id}")
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_UPDATE)
    @AuditLog(action = "scheduler.job.update", targetType = "scheduler-job", targetId = "#id",
            contentParams = {"request"}, beforeQuery = "@jobUseCase.getSnapshot(#id)")
    public JobView update(@PathVariable Long id, @Valid @RequestBody JobSaveRequest request) {
        return jobUseCase.update(id, request);
    }

    /** 启停专用端点（确认②；启用计算初始排期，停用移除时间轮条目）。 */
    @PutMapping("/{id}/status")
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_UPDATE)
    @AuditLog(action = "scheduler.job.updateStatus", targetType = "scheduler-job", targetId = "#id",
            contentParams = {"request.status"}, beforeQuery = "@jobUseCase.getSnapshot(#id)")
    public JobView updateStatus(@PathVariable Long id, @Valid @RequestBody JobStatusUpdateRequest request) {
        return jobUseCase.updateStatus(id, request.status());
    }

    /** 手动触发（POST /{id}/trigger，MANUAL execution，返回执行记录 id；权限 job:update）。 */
    @PostMapping("/{id}/trigger")
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_UPDATE)
    @AuditLog(action = "scheduler.job.trigger", targetType = "scheduler-job", targetId = "#id")
    public Long trigger(@PathVariable Long id) {
        return jobUseCase.trigger(id);
    }

    /** 删除（软删；执行历史保留）。 */
    @DeleteMapping("/{id}")
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_DELETE)
    @AuditLog(action = "scheduler.job.delete", targetType = "scheduler-job", targetId = "#id",
            beforeQuery = "@jobUseCase.getSnapshot(#id)")
    public void delete(@PathVariable Long id) {
        jobUseCase.delete(id);
    }
}
