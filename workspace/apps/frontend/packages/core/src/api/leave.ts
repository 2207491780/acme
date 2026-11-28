import { apiFetch, apiJson } from '../http/client';

/**
 * 请假域消费面（REQ-20261124-213008 契约⑥⑦）：/admin/v1/leave/** 的类型与调用封装。
 * 无权限点（B 端用户令牌面，认证即可）；遵循 REQ-20261122-233425 确认⑦：
 * 不进菜单、审批面走待办中心；失败沿用统一响应体（Result 信封，由 apiFetch/apiJson 解析）。
 *
 * ⚠️ 形态约束（REQ-20261127-161433 P1-09、决策② 2026-11-28）：本域请求与通用 `/admin` 面
 * 共享 base URL、无独立切换开关——而网关路由白名单**未登记** `/admin/v1/leave/**`，
 * 分布式五件套形态下这些请求到网关即 404。**仅在单体形态端点可达（单体试点）**；
 * 上分布式栈另立专项（届时补网关路由 + 服务注册 + business-leave application.yml 基线）。
 * 部署形态说明见 env-stack/ENV-STACK.md「双形态·形态边界标注」。
 */

/** 请假单状态（契约状态机：APPLYING→APPROVING→FILED→DONE/REJECTED；中文映射经 i18n workflow.leave.status）。 */
export type LeaveStatus = 'APPLYING' | 'APPROVING' | 'FILED' | 'DONE' | 'REJECTED';

/** 请假单视图（契约对齐 LeaveRequestView；id/instanceId wire 字符串口径；instanceId 可空=未关联流程实例）。 */
export interface LeaveRequestView {
  id: string;
  userName: string;
  leaveType: string;
  reason: string | null;
  /** 开始时间（ISO-8601 Instant 字符串）。 */
  startTime: string;
  /** 结束时间（ISO-8601 Instant 字符串）。 */
  endTime: string;
  status: LeaveStatus;
  /** 关联流程实例 id（可空；发起成功后由后端异步落定）。 */
  instanceId: string | null;
  /** 业务键（流程实例对账锚点；前端生成 'LEAVE-{uuid}'）。 */
  businessKey: string;
}

/** 请假发起载荷（契约对齐 LeaveApplyCommand）：businessKey/leaveType/startTime/endTime 必填、reason 可空；
 *  startTime/endTime 由调用方转 ISO-8601 后提交。 */
export interface LeaveApplyPayload {
  businessKey: string;
  leaveType: string;
  reason?: string | null;
  startTime: string;
  endTime: string;
}

/** 请假域接口（REQ-20261124-213008 契约⑥⑦两端点；鉴权=B 端用户令牌面，无权限点）。 */
export const leaveApi = {
  /** 发起请假（POST /admin/v1/leave/requests）：返回 LeaveRequestView（APPLYING 起步）；
   *  businessKey=流程实例对账锚点，前端生成 'LEAVE-{uuid}'。 */
  apply: (payload: LeaveApplyPayload): Promise<LeaveRequestView> =>
    apiJson<LeaveRequestView>('/admin/v1/leave/requests', 'POST', payload),
  /** 请假单详情（GET /admin/v1/leave/requests/{id}）：{id} 为请假单 id（wire Long→string 口径）。
   *  signal 为可选取消信号，透传 apiFetch。 */
  get: (id: string, signal?: AbortSignal): Promise<LeaveRequestView> =>
    apiFetch<LeaveRequestView>(`/admin/v1/leave/requests/${encodeURIComponent(id)}`, { signal }),
};
