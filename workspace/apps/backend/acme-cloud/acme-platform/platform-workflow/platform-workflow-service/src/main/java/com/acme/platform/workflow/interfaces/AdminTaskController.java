package com.acme.platform.workflow.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.workflow.api.dto.TaskCompleteRequest;
import com.acme.platform.workflow.api.dto.TaskDelegateRequest;
import com.acme.platform.workflow.api.dto.TaskRejectRequest;
import com.acme.platform.workflow.api.dto.TaskTransferRequest;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.api.dto.TaskView;
import com.acme.platform.workflow.application.TaskUseCase;
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

import java.util.Map;

/**
 * 任务操作 B 端接口（契约 API 任务域 8 端点；权限点 workflow:task:list / workflow:task:operate
 * ——确认⑦）：
 * <ul>
 *   <li>GET  /admin/v1/workflow/tasks/todo | /tasks/done → workflow:task:list（81，我的待办/已办）</li>
 *   <li>POST /admin/v1/workflow/tasks/{taskId}/complete | reject | claim | unclaim | transfer | delegate
 *       → workflow:task:operate（82）</li>
 * </ul>
 * 「我」语义=当前登录用户（UserContext，对齐 MeController 取 me 机制）；ALL 策略：候选可见可办。
 */
@RestController
@RequestMapping("/admin/v1/workflow/tasks")
public class AdminTaskController {

    private final TaskUseCase taskUseCase;

    public AdminTaskController(TaskUseCase taskUseCase) {
        this.taskUseCase = taskUseCase;
    }

    /** 我的待办（assignee=me 或候选含我，租户隔离；definitionKey/status 可选过滤——确认⑤）。 */
    @GetMapping("/todo")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_LIST)
    public PageResult<TaskView> todo(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size,
                                     @RequestParam(required = false) String definitionKey,
                                     @RequestParam(required = false) WorkflowTaskStatus status) {
        return taskUseCase.todo(currentUserId(), new PageQuery(page, size), definitionKey, status);
    }

    /** 我的已办（COMPLETED 且完成时 assignee=me；definitionKey/status 可选过滤——确认⑤）。 */
    @GetMapping("/done")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_LIST)
    public PageResult<TaskView> done(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size,
                                     @RequestParam(required = false) String definitionKey,
                                     @RequestParam(required = false) WorkflowTaskStatus status) {
        return taskUseCase.done(currentUserId(), new PageQuery(page, size), definitionKey, status);
    }

    /** 办结（表单落 form_data jsonb；隐式认领锚点）。 */
    @PostMapping("/{taskId}/complete")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_OPERATE)
    public Map<String, Object> complete(@PathVariable Long taskId,
                                        @RequestBody(required = false) TaskCompleteRequest request) {
        Long id = taskUseCase.complete(taskId, request == null ? null : request.form());
        return Map.of("taskId", String.valueOf(id), "status", "COMPLETED");
    }

    /** 驳回（targetNodeId 缺省=上一 UserTask / 指定回退；历史链 REJECTED 边——AC-6）。 */
    @PostMapping("/{taskId}/reject")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_OPERATE)
    public Map<String, Object> reject(@PathVariable Long taskId,
                                      @RequestBody(required = false) TaskRejectRequest request) {
        Long id = taskUseCase.reject(taskId, request == null ? null : request.targetNodeId(),
                request == null ? null : request.comment());
        return Map.of("taskId", String.valueOf(id), "status", "REJECTED");
    }

    /** 认领（候选语义 ALL；claim 后 assignee 生效——AC-5）。 */
    @PostMapping("/{taskId}/claim")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_OPERATE)
    public Map<String, Object> claim(@PathVariable Long taskId) {
        Long id = taskUseCase.claim(taskId);
        return Map.of("taskId", String.valueOf(id), "status", "CLAIMED");
    }

    /** 撤认（回候选池；未认领 unclaim → STATE_CONFLICT——AC-6）。 */
    @PostMapping("/{taskId}/unclaim")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_OPERATE)
    public Map<String, Object> unclaim(@PathVariable Long taskId) {
        Long id = taskUseCase.unclaim(taskId);
        return Map.of("taskId", String.valueOf(id), "status", "CREATED");
    }

    /** 转办（重设 assignee）。 */
    @PostMapping("/{taskId}/transfer")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_OPERATE)
    public Map<String, Object> transfer(@PathVariable Long taskId,
                                        @Valid @RequestBody TaskTransferRequest request) {
        String assignee = taskUseCase.transfer(taskId, request.assignee());
        return Map.of("taskId", String.valueOf(taskId), "assignee", assignee);
    }

    /** 委派（转办+owner 记录，委托链单层——确认⑤）。 */
    @PostMapping("/{taskId}/delegate")
    @RequirePermission(SystemPermissions.WORKFLOW_TASK_OPERATE)
    public Map<String, Object> delegate(@PathVariable Long taskId,
                                        @Valid @RequestBody TaskDelegateRequest request) {
        String assignee = taskUseCase.delegate(taskId, request.assignee());
        return Map.of("taskId", String.valueOf(taskId), "assignee", assignee);
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
