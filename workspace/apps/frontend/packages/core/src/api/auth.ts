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

/** 租户公开站点配置视图（契约对齐 TenantPublicConfigView，REQ-20261117-025200：仅暴露页脚两字段；
 *  无行/未知租户 → 缺省（null,false）；登录页页脚显隐依据，非安全面 fail-open）。 */
export interface TenantPublicConfigView {
  footerCopyright: string | null;
  footerVisible: boolean;
}

/** B 端登录提交（契约对齐 AdminLoginRequest；REQ-20261117-011514：租户策略关时验证码字段整体省略）。 */
export interface LoginPayload {
  tenantCode: string;
  username: string;
  password: string;
  captchaId?: string;
  captchaCode?: string;
}

/** 令牌签发结果（契约对齐 AdminTokenIssued；expiresAt 为 ISO-8601 字符串）。
 *  userId 为字符串化雪花 ID（wire string，防 JS Number 精度丢失），勿改回 number。 */
export interface TokenIssued {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  userId: string;
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

/** 批量下线结果（REQ-20261117-061017，契约对齐 BatchKickView）：
 *  kickedCount=已撤销会话数；skippedCount=超管会话跳过数（超管保护口径）。 */
export interface BatchKickView {
  kickedCount: number;
  skippedCount: number;
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
  /** 公开站点配置（公开端点，REQ-20261117-025200；登录页页脚显隐依据，解包口径同 captchaPolicy；
   *  无行/未知租户后端返回缺省（null,false），system 不可用 fail-open 亦缺省）。 */
  publicConfig: (tenantCode: string): Promise<TenantPublicConfigView> =>
    apiFetch<TenantPublicConfigView>(
      `/admin/v1/auth/public-config?tenantCode=${encodeURIComponent(tenantCode)}`,
      { method: 'GET' },
      { auth: false },
    ),
  /** 超管跨租户切换（认证端点，REQ-20261117-025200；非超管 403，目标租户未知/停用/过期业务错误码）。
   *  成功=签发目标租户新令牌并互踢旧会话；返回 AdminTokenIssued（userId wire string，同 login）。 */
  tenantSwitch: (targetTenantCode: string): Promise<TokenIssued> =>
    apiFetch<TokenIssued>('/admin/v1/auth/tenant-switch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ targetTenantCode }),
    }),
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
  /** 按用户名批量下线（REQ-20261117-061017；权限点 auth:online:revoke）：
   *  DELETE /admin/v1/auth/online-users?username={username}（query 参数，username 必填）——
   *  枚举该用户全部 ACTIVE 未过期会话逐个 kick（超管会话跳过并计入 skippedCount），
   *  返回 BatchKickView（kickedCount/skippedCount）。 */
  onlineUsersBatchKick: (username: string): Promise<BatchKickView> => {
    const qs = new URLSearchParams({ username });
    return apiFetch<BatchKickView>(`/admin/v1/auth/online-users?${qs.toString()}`, { method: 'DELETE' });
  },
  /** 踢下线（权限点 auth:online:revoke；目标不存在=SESSION_NOT_FOUND，非超管踢超管=REVOKE_SUPERADMIN_FORBIDDEN，
   *  业务失败=Result.code≠0（HTTP 200）→ 由 apiFetch 抛 ApiError）。 */
  revokeOnlineUser: (sessionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/auth/online-users/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }),
  /** 登录日志导出 URL（REQ-20261117-061017；xlsx 附件豁免信封，权限复用 auth:loginlog:list；
   *  同筛选参数；交 useExcelExport.exportFile(url) 走 downloadFile 二进制下载）。 */
  loginLogsExport: (filters: LoginLogFilters = {}): string => {
    const qs = new URLSearchParams();
    if (filters.username) qs.set('username', filters.username);
    if (filters.userType) qs.set('userType', filters.userType);
    if (filters.result) qs.set('result', filters.result);
    if (filters.from) qs.set('from', filters.from);
    if (filters.to) qs.set('to', filters.to);
    const query = qs.toString();
    return query ? `/admin/v1/auth/login-logs/export?${query}` : '/admin/v1/auth/login-logs/export';
  },
};
