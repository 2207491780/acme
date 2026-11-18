import { authHandlers } from './handlers/auth';
import { systemHandlers } from './handlers/system';
import { notifyHandlers } from './handlers/notify';
import { infraHandlers } from './handlers/infra';

/**
 * MSW handler 聚合（REQ-20261114-120103，AC-7）：按域拆分（auth/system/notify/infra），
 * 覆盖核心端点（me/用户/角色/权限/菜单/字典/公告/站内信/文件/审计/认证）。
 * 未覆盖端点经 onUnhandledRequest: 'bypass' 透传（dev 代理到真实 WEB_API_TARGET）。
 */
export const handlers = [...authHandlers, ...systemHandlers, ...notifyHandlers, ...infraHandlers];
