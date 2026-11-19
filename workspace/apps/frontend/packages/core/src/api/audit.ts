import { apiFetch, buildQuery } from '../http/client';
import type { PageResult } from './system';

/** 审计事件视图（契约对齐 AdminAuditEventView；id 序列化为字符串）。 */
export interface AdminAuditEventView {
  id: string;
  eventId: string;
  traceId: string;
  spanId: string;
  tenantId: string;
  operatorId: string;
  channel: string;
  action: string;
  targetType: string;
  targetId: string;
  content: string;
  occurredAt: string;
  createdAt: string;
}

/** 基础设施域接口（platform-infra；P3 审计事件查询打通）。 */
export const auditApi = {
  /** 审计事件分页（权限点 infra:audit:list；具备 list:all 时为全租户视图；signal 为可选取消信号）。
   *  from/to 为 ISO-8601 时间串（后端 @DateTimeFormat ISO DATE_TIME）。 */
  events: (
    page: number,
    size: number,
    filters: { operatorId?: string; action?: string; traceId?: string; from?: string; to?: string } = {},
    signal?: AbortSignal,
  ): Promise<PageResult<AdminAuditEventView>> =>
    apiFetch<PageResult<AdminAuditEventView>>(
      `/admin/v1/infra/audit-events?${buildQuery({ page, size, operatorId: filters.operatorId, action: filters.action, traceId: filters.traceId, from: filters.from, to: filters.to })}`,
      { signal },
    ),
  /** 审计事件导出 URL（REQ-20261117-061017；xlsx 附件豁免信封，权限复用 infra:audit:list；
   *  同筛选参数；交 useExcelExport.exportFile(url) 走 downloadFile 二进制下载）。 */
  eventsExport: (filters: { operatorId?: string; action?: string; traceId?: string; from?: string; to?: string } = {}): string => {
    const query = buildQuery({ operatorId: filters.operatorId, action: filters.action, traceId: filters.traceId, from: filters.from, to: filters.to });
    return query ? `/admin/v1/infra/audit-events/export?${query}` : '/admin/v1/infra/audit-events/export';
  },
};
