package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveStatus;

import java.time.Instant;

/**
 * 请假单视图（申请返回面；businessKey=请假单 id 字符串形态——流程实例对账/事件回写锚点）。
 *
 * @param id         请假单 id
 * @param userName   申请人姓名
 * @param leaveType  请假类型
 * @param reason     事由
 * @param startTime  开始时刻
 * @param endTime    结束时刻
 * @param status     状态（APPLYING→APPROVING→FILED→DONE/REJECTED）
 * @param instanceId 流程实例 id（启动成功回写；可空=尚未启动）
 * @param businessKey 业务键（=id 字符串）
 */
public record LeaveRequestView(Long id, String userName, String leaveType, String reason,
                               Instant startTime, Instant endTime, LeaveStatus status,
                               Long instanceId, String businessKey) {
}
