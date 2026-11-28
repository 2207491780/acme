package com.acme.platform.scheduler.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.application.ExecutionView;
import com.acme.platform.scheduler.application.ExecutorView;
import com.acme.platform.scheduler.application.ExecutorUseCase;
import com.acme.platform.scheduler.application.ExecutionQueryUseCase;
import com.acme.framework.web.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 执行面 B 端查询接口（契约确认②权限终稿：executions 归 scheduler:execution:list（77）；
 * executors 归 scheduler:job:list（73）；设计规格 §6 执行历史/执行器监控数据面）。
 */
@RestController
public class AdminExecutionController {

    private final ExecutionQueryUseCase executionQueryUseCase;
    private final ExecutorUseCase executorUseCase;

    public AdminExecutionController(ExecutionQueryUseCase executionQueryUseCase,
                                    ExecutorUseCase executorUseCase) {
        this.executionQueryUseCase = executionQueryUseCase;
        this.executorUseCase = executorUseCase;
    }

    /**
     * 执行历史分页（确认④：按 jobKey/state/fireTime 区间过滤；fire_time 倒序稳定排序）。
     */
    @GetMapping("/admin/v1/scheduler/executions")
    @RequirePermission(SystemPermissions.SCHEDULER_EXECUTION_LIST)
    public PageResult<ExecutionView> executions(
            @RequestParam(required = false) String jobKey,
            @RequestParam(required = false) ExecutionState state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fireTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fireTimeTo,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return executionQueryUseCase.list(jobKey, state, fireTimeFrom, fireTimeTo,
                new PageQuery(page, size));
    }

    /** 执行器清单（含动态在线状态，AC-10；权限点=job:list 73）。 */
    @GetMapping("/admin/v1/scheduler/executors")
    @RequirePermission(SystemPermissions.SCHEDULER_JOB_LIST)
    public List<ExecutorView> executors() {
        return executorUseCase.list();
    }
}
