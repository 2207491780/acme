package com.acme.business.leave.interfaces;

import com.acme.business.leave.application.LeaveApplyCommand;
import com.acme.business.leave.application.LeaveRequestUseCase;
import com.acme.business.leave.application.LeaveRequestView;
import com.acme.common.core.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 请假申请用户面（REQ-20261122-233425 确认⑤；POST /admin/v1/leave/requests——经 SDK 启动流程
 * 绑 businessKey；审批走 web 待办中心用户面 BPM-4 已交付，无新权限点——契约确认⑦不进菜单，
 * 鉴权=B 端用户令牌面（BootstrapAuthFilter/网关），无细粒度权限注解）。
 */
@RestController
@RequestMapping("/admin/v1/leave")
public class LeaveRequestController {

    private final LeaveRequestUseCase leaveRequestUseCase;

    public LeaveRequestController(LeaveRequestUseCase leaveRequestUseCase) {
        this.leaveRequestUseCase = leaveRequestUseCase;
    }

    /** 提交申请（落库 APPLYING → WorkflowRuntimeClient 启动流程 → APPROVING）。 */
    @PostMapping("/requests")
    public Result<LeaveRequestView> apply(@Valid @RequestBody LeaveApplyCommand command) {
        return Result.ok(leaveRequestUseCase.apply(command));
    }

    /** 详情（按请假单 id；状态核对面）。 */
    @GetMapping("/requests/{id}")
    public Result<LeaveRequestView> get(@PathVariable Long id) {
        return Result.ok(leaveRequestUseCase.get(id));
    }
}
