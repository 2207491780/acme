import { http } from 'msw';
import * as db from '../data';
import { bodyOf, fail, ok } from '../utils';
import type { LeaveRequestView, LeaveStatus } from '@acme/core';

/**
 * 请假域 handler（REQ-20261124-213008 契约⑥⑦两端点，与后端契约同形）：
 * POST /admin/v1/leave/requests（发起）+ GET /admin/v1/leave/requests/{id}（详情）。
 * 无权限点（B 端用户令牌面）；失败沿用统一信封（Result.fail，code/message/data=null）。
 * wire 口径：id/instanceId 字符串；instanceId 可空（mock 口径：发起落库为 null）。
 * 演示触发（供测试/联调）：leaveType 含 __INVALID__ → LEAVE_TYPE_INVALID（后端校验非 0 code 转译演示）。
 */

const store = { requests: [...db.leaveRequests] };

/** fixture 至 8002；新发起请假单自 8010 起（避免与种子段冲突）。 */
let nextId = 8010;
const genId = (): string => String(nextId++);

/** 合法请假状态全集（wire 字面量校验面，防 fixture 漂移）。 */
const isLeaveStatus = (value: unknown): value is LeaveStatus =>
  value === 'APPLYING' || value === 'APPROVING' || value === 'FILED' || value === 'DONE' || value === 'REJECTED';

export const leaveHandlers = [
  // 1) POST 发起：必填校验（businessKey/leaveType/startTime/endTime）+ 时间合法性（endTime >= startTime）
  http.post('/admin/v1/leave/requests', async ({ request }) => {
    const body = await bodyOf(request);
    const businessKey = typeof body.businessKey === 'string' ? body.businessKey.trim() : '';
    const leaveType = typeof body.leaveType === 'string' ? body.leaveType.trim() : '';
    const startTime = typeof body.startTime === 'string' ? body.startTime : '';
    const endTime = typeof body.endTime === 'string' ? body.endTime : '';
    if (!businessKey || !leaveType || !startTime || !endTime) {
      return fail('PARAM_INVALID', 'businessKey/leaveType/startTime/endTime 必填', 400);
    }
    if (leaveType.includes('__INVALID__')) {
      return fail('LEAVE_TYPE_INVALID', '请假类型无效', 400);
    }
    const startMs = Number.isNaN(Date.parse(startTime)) ? NaN : Date.parse(startTime);
    const endMs = Number.isNaN(Date.parse(endTime)) ? NaN : Date.parse(endTime);
    if (Number.isNaN(startMs) || Number.isNaN(endMs)) {
      return fail('PARAM_INVALID', 'startTime/endTime 必须为 ISO-8601 时间', 400);
    }
    if (endMs < startMs) {
      return fail('PARAM_INVALID', 'endTime 不得早于 startTime', 400);
    }
    const created: LeaveRequestView = {
      id: genId(),
      userName: db.me.user.username,
      leaveType,
      reason: typeof body.reason === 'string' && body.reason.trim() !== '' ? body.reason : null,
      startTime,
      endTime,
      status: 'APPLYING',
      instanceId: null,
      businessKey,
    };
    store.requests = [created, ...store.requests];
    return ok(created);
  }),

  // 2) GET 详情：按请假单 id（wire string）；不存在 → LEAVE_REQUEST_NOT_FOUND
  http.get('/admin/v1/leave/requests/:requestId', ({ params }) => {
    const target = store.requests.find((row) => row.id === String(params.requestId));
    if (!target || !isLeaveStatus(target.status)) {
      return fail('LEAVE_REQUEST_NOT_FOUND', '请假单不存在', 404);
    }
    return ok(target);
  }),
];
