import { apiFetch } from '../http/client';
import type { PageResult } from './system';

/** 启用租户摘要（login-tenant-select）。 */
export interface TenantSummary {
  tenantCode: string;
  tenantName: string;
}

/** 图形验证码（login-guard-captcha；imageBase64Data 为 data:image/png;base64 data-URI）。 */
export interface CaptchaData {
  captchaId: string;
  imageBase64Data: string;
}

/** 验证码策略视图（契约对齐 CaptchaPolicyView，REQ-20261117-011514：按 tenantCode 返回当前生效值，
 *  无配置行=false；下游不可用后端 fail-closed 返回 true）。 */
export interface CaptchaPolicyView {
  captchaEnabled: boolean;
}

/** B 端登录提交（契约对齐 AdminLoginRequest；REQ-20261117-011514：租户策略关时验证码字段整体省略）。 */
export interface LoginPayload {
  tenantCode: string;
  username: string;
  password: string;
  captchaId?: string;
  captchaCode?: string;
}

/** 令牌签发结果（契约对齐 AdminTokenIssued；expiresAt 为 ISO-8601 字符串）。 */
export interface TokenIssued {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  userId: number;
  username: string;
  nickname: string | null;
}

/** 登录日志视图（契约对齐 sys_login_log：username/user_type/ip/user_agent/result/fail_reason/trace_id/created_at；
 *  仅插入不改。id 序列化为字符串，时间 ISO-8601，对齐 Admin*View 惯例）。 */
export interface AdminLoginLogView {
  id: string;
  username: string;
  userType: string;
  ip: string;
  userAgent: string;
  result: string;
  failReason: string | null;
  traceId: string | null;
  createdAt: string;
}

/** 在线用户视图（契约对齐活跃会话列表：username/ip/登录时间/最近活跃/userType）。 */
export interface AdminOnlineUserView {
  sessionId: string;
  username: string;
  userType: string;
  ip: string;
  loginAt: string;
  lastActiveAt: string;
}

/** 在线用户行筛选（登录日志分页查询条件）。 */
export interface LoginLogFilters {
  username?: string;
  userType?: string;
  result?: string;
  from?: string;
  to?: string;
}

/** 登录域接口（认证域契约：图形验证码 / 租户目录 / 登录 / 登出）。 */
export const authApi = {
  /** 启用租户清单（公开端点；登录页可搜索下拉数据源）。 */
  tenants: (): Promise<TenantSummary[]> =>
    apiFetch<TenantSummary[]>('/admin/v1/auth/tenants', { method: 'GET' }, { auth: false }),
  /** 验证码策略（公开端点，REQ-20261117-011514；登录页显隐/提交依据；tenantCode 空白→400 参数错误）。 */
  captchaPolicy: (tenantCode: string): Promise<CaptchaPolicyView> =>
    apiFetch<CaptchaPolicyView>(
      `/admin/v1/auth/captcha-policy?tenantCode=${encodeURIComponent(tenantCode)}`,
      { method: 'GET' },
      { auth: false },
    ),
  /** 图形验证码（公开端点；captchaId 登录时原样回传）。 */
  captcha: (): Promise<CaptchaData> =>
    apiFetch<CaptchaData>('/admin/v1/auth/captcha', { method: 'GET' }, { auth: false }),
  /** 登录（公开端点；成功签发 B 端 JWS 访问令牌，默认 30 分钟短会话）。 */
  login: (payload: LoginPayload): Promise<TokenIssued> =>
    apiFetch<TokenIssued>('/admin/v1/auth/tokens', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }, { auth: false }),
  /** 登出（需认证；当前会话即刻失效，已失效令牌幂等成功；void 端点 2xx 空体=成功）。 */
  logout: (): Promise<void> =>
    apiFetch<void>('/admin/v1/auth/tokens/current', { method: 'DELETE' }, { emptyAsSuccess: true }),
  /** 登录日志分页（权限点 auth:loginlog:list；username/userType/result/时间区间筛选；
   *  from/to 为 ISO-8601 时间串；signal 为可选取消信号）。 */
  loginLogs: (
    page: number,
    size: number,
    filters: LoginLogFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<AdminLoginLogView>> => {
    const qs = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters.username) qs.set('username', filters.username);
    if (filters.userType) qs.set('userType', filters.userType);
    if (filters.result) qs.set('result', filters.result);
    if (filters.from) qs.set('from', filters.from);
    if (filters.to) qs.set('to', filters.to);
    return apiFetch<PageResult<AdminLoginLogView>>(`/admin/v1/auth/login-logs?${qs.toString()}`, { signal });
  },
  /** 在线用户列表（权限点 auth:online:list；来源=AuthSession 未过期集合，非分页数组）。 */
  onlineUsers: (signal?: AbortSignal): Promise<AdminOnlineUserView[]> =>
    apiFetch<AdminOnlineUserView[]>('/admin/v1/auth/online-users', { signal }),
  /** 踢下线（权限点 auth:online:revoke；目标不存在=SESSION_NOT_FOUND，非超管踢超管=REVOKE_SUPERADMIN_FORBIDDEN，
   *  业务失败=Result.code≠0（HTTP 200）→ 由 apiFetch 抛 ApiError）。 */
  revokeOnlineUser: (sessionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/auth/online-users/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }),
};
