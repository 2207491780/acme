package com.acme.platform.workflow.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.dto.HistoryActivityView;
import com.acme.platform.workflow.api.dto.ProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.ProcessInstanceSuspendRequest;
import com.acme.platform.workflow.api.dto.ProcessInstanceView;
import com.acme.platform.workflow.application.InstanceUseCase;
import com.acme.framework.web.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程实例管理 B 端接口（契约 API 实例域 7 端点；权限点 workflow:instance:manage——确认⑦，
 * 含 history/initiated/detail 查询面）：
 * <ul>
 *   <li>POST /admin/v1/workflow/process-instances               → 启动（DEPLOYED 校验 + StartEvent 推进）</li>
 *   <li>GET  /admin/v1/workflow/process-instances/{id}          → 实例+活跃节点+任务树+变量摘要</li>
 *   <li>GET  /admin/v1/workflow/process-instances/initiated     → 我发起（字面路径优先于 {id} 模板）</li>
 *   <li>GET  /admin/v1/workflow/process-instances               → 分页（definitionKey/status/businessKey）</li>
 *   <li>POST /admin/v1/workflow/process-instances/{id}/terminate | suspend | resume</li>
 *   <li>GET  /admin/v1/workflow/process-instances/{id}/history  → 历史活动链（AC-15）</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/v1/workflow/process-instances")
public class AdminProcessInstanceController {

    private final InstanceUseCase instanceUseCase;

    public AdminProcessInstanceController(InstanceUseCase instanceUseCase) {
        this.instanceUseCase = instanceUseCase;
    }

    /** 启动（定义 DEPLOYED 校验；businessKey 必填；初始化 StartEvent → 首个行为推进）。 */
    @PostMapping
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public ProcessInstanceView start(@Valid @RequestBody ProcessInstanceStartRequest request) {
        Long id = instanceUseCase.start(request.definitionId(), request.businessKey(),
                request.variables());
        return instanceUseCase.detail(id);
    }

    /** 我发起（initiator=当前登录用户——「我」语义对齐 MeController 取 me 机制）。 */
    @GetMapping("/initiated")
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public PageResult<ProcessInstanceView> initiated(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return instanceUseCase.initiated(currentUserId(), new PageQuery(page, size));
    }

    /** 详情（实例+活跃节点+任务树+变量摘要）。 */
    @GetMapping("/{id}")
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public ProcessInstanceView detail(@PathVariable Long id) {
        return instanceUseCase.detail(id);
    }

    /** 分页（definitionKey/status/businessKey 过滤）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public PageResult<ProcessInstanceView> list(
            @RequestParam(required = false) String definitionKey,
            @RequestParam(required = false) WorkflowInstanceStatus status,
            @RequestParam(required = false) String businessKey,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return instanceUseCase.list(definitionKey, status, businessKey, new PageQuery(page, size));
    }

    /** 终止（撤回=实例 TERMINATED + 未完成全 CANCELLED——确认⑤）。 */
    @PostMapping("/{id}/terminate")
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public ProcessInstanceView terminate(@PathVariable Long id) {
        instanceUseCase.terminate(id);
        return instanceUseCase.detail(id);
    }

    /** 挂起（SUSPENDED 拒绝新操作）。 */
    @PostMapping("/{id}/suspend")
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public ProcessInstanceView suspend(@PathVariable Long id,
                                       @RequestBody(required = false)
                                       ProcessInstanceSuspendRequest request) {
        instanceUseCase.suspend(id, request == null ? null : request.resolveReason());
        return instanceUseCase.detail(id);
    }

    /** 恢复（继续推进）。 */
    @PostMapping("/{id}/resume")
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public ProcessInstanceView resume(@PathVariable Long id) {
        instanceUseCase.resume(id);
        return instanceUseCase.detail(id);
    }

    /** 历史活动链（ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE 按序完整——AC-15）。 */
    @GetMapping("/{id}/history")
    @RequirePermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)
    public List<HistoryActivityView> history(@PathVariable Long id) {
        return instanceUseCase.history(id);
    }

    /** 当前登录用户（UserContext——网关/边缘过滤链绑定，FR-007）。 */
    private static String currentUserId() {
        com.acme.common.context.UserContext user = com.acme.common.context.UserContext.get();
        if (user == null || user.userId() == null || user.userId().isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("缺少用户上下文");
        }
        return user.userId();
    }
}
