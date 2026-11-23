package com.acme.business.leave.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 请假申请命令（POST /admin/v1/leave/requests 用户面；REQ-20261122-233425 确认⑤）。
 * user_id 取当前登录上下文；tenant_id 取租户上下文（网关/边缘过滤链绑定，FR-007）；
 * user_name 展示列以 userId 兜底填充（契约⑤表字段域）；definitionId 由试点流程供给 SPI
 * {@code LeaveProcessLauncher} 自持（业务侧不感知定义 id）。
 *
 * @param businessKey 业务键（必填——流程实例对账/事件回写锚点，调用方语义自定）
 * @param leaveType   请假类型（事假/年假/病假等展示域）
 * @param reason      事由
 * @param startTime   开始时刻
 * @param endTime     结束时刻
 */
public record LeaveApplyCommand(
        @NotBlank String businessKey,
        @NotBlank String leaveType,
        String reason,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
