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

    /**
     * 提交申请（REQ-20261128-092210 P1-03：路径/@Valid 校验/鉴权不变——同事务落库
     * leave_request(APPLYING)+补偿任务行 leave_start_task(PENDING)；流程启动后置 afterCommit，
     * 成功 instance_id 于响应前异步回写（响应 view.instanceId 恒 null，可空字段前端已兼容），
     * 失败转补偿重试面，响应恒 2xx）。
     */
    @PostMapping("/requests")
    public Result<LeaveRequestView> apply(@Valid @RequestBody LeaveApplyCommand command) {
        return Result.ok(leaveRequestUseCase.apply(command));
    }

    /**
     * 详情（按请假单 id；状态核对面）。细粒度口径（REQ-20261127-235808 决策③/确认⑦案B）：
     * owner 直读；非 owner 持 {@code workflow:task:list} 或 {@code workflow:instance:manage}
     * 任一权限码放行（经 platform-system 实查）；全不命中与「单据不存在」同口径（HTTP 400，
     * 不泄露单据存在性）。不加 all-or-nothing 权限注解门（与 owner-or-permission 语义不符）。
     * instanceId=流程启动异步回写结果（REQ-20261128-092210 P1-03），启动成功前为 null。
     */
    @GetMapping("/requests/{id}")
    public Result<LeaveRequestView> get(@PathVariable Long id) {
        return Result.ok(leaveRequestUseCase.get(id));
    }
}
