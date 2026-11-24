import { authHandlers } from './handlers/auth';
import { systemHandlers } from './handlers/system';
import { notifyHandlers } from './handlers/notify';
import { infraHandlers } from './handlers/infra';
import { schedulerHandlers } from './handlers/scheduler';
import { workflowHandlers } from './handlers/workflow';
import { leaveHandlers } from './handlers/leave';

/**
 * MSW handler 聚合（REQ-20261114-120103，AC-7）：按域拆分（auth/system/notify/infra/scheduler/workflow/leave），
 * 覆盖核心端点（me/用户/角色/权限/菜单/字典/公告/站内信/文件/审计/认证/调度中心/流程定义/请假）。
 * 未覆盖端点经 onUnhandledRequest: 'bypass' 透传（dev 代理到真实 WEB_API_TARGET）。
 */
export const handlers = [...authHandlers, ...systemHandlers, ...notifyHandlers, ...infraHandlers, ...schedulerHandlers, ...workflowHandlers, ...leaveHandlers];

