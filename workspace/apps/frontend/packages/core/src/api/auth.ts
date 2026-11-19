import { apiFetch, apiJson, buildQuery } from '../http/client';
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

/** 扫码票据状态（契约 REQ-20261119-040838：created/scanned/confirmed/cancelled/expired）。
 *  未知/过期 ticketId → expired；confirmed 为唯一终态携 token，其余状态 token=null。 */
export type QrTicketStatus = 'created' | 'scanned' | 'confirmed' | 'cancelled' | 'expired';

/** 创建扫码票据视图（契约对齐 QrTicketCreatedView：expiresInSeconds=120；qrPayload 为待编码进
 *  二维码的紧凑 JSON{t:ticketId} 字符串，web 端用 qrcode 库渲染 dataURL）。 */
export interface QrTicketCreatedView {
  ticketId: string;
  qrPayload: string;
  expiresInSeconds: number;
}

/** 轮询票据状态视图（契约对齐 QrTicketStatusView；GET 只读不消费票；
 *  confirmed 时 token 携 AdminTokenIssued（确认者 B 端令牌，userId wire string））。 */
export interface QrTicketStatusView {
  status: QrTicketStatus;
  token: TokenIssued | null;
}

/** 扫码取信息视图（契约对齐 QrTicketInfoView；APP B 令牌调用，status created→scanned 幂等）。 */
export interface QrTicketInfoView {
  status: QrTicketStatus;
  createdAt: string;
}

/** 本人在线会话视图（REQ-20261119-082531 契约④）：tokenId=令牌 jti；current=当前请求所持会话；
 *  issuedAt/lastActiveAt 为 ISO-8601 时间串。仅返回本人 ACTIVE 未过期会话（免权限点）。 */
export interface SelfSessionView {
  tokenId: string;
  ip: string;
  issuedAt: string;
  lastActiveAt: string;
  current: boolean;
}

/** 自助改密载荷（REQ-20261119-082531 契约③）：旧密码 BCrypt 校验（失败 OLD_PASSWORD_INVALID），
 *  新密码 8-64 策略且不得与旧密码相同（SAME_PASSWORD）；成功后其他会话全部 KICKED+撤销、当前会话保持。 */
export interface SelfPasswordPayload {
  oldPassword: string;
  newPassword: string;
}

/** 登录域接口（认证域契约：图形验证码 / 租户目录 / 登录 / 登出 / 扫码票据）。 */
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
    apiJson<TokenIssued>('/admin/v1/auth/tenant-switch', 'POST', { targetTenantCode }),
  /** 登录（公开端点；成功签发 B 端 JWS 访问令牌，默认 30 分钟短会话）。 */
  login: (payload: LoginPayload): Promise<TokenIssued> =>
    apiJson<TokenIssued>('/admin/v1/auth/tokens', 'POST', payload, { auth: false }),
  // ————— 扫码登录票据（REQ-20261119-040838：web 登录页扫码 Tab ↔ APP 员工扫码确认） —————
  /** 创建扫码票据（公开端点，契约 API①；无 body，clientIp 服务端从请求取；
   *  IP 频控 10 次/分钟，超限后端 QR_TICKET_LIMITED 429）。 */
  qrCreate: (): Promise<QrTicketCreatedView> =>
    apiJson<QrTicketCreatedView>('/admin/v1/auth/qr-tickets', 'POST', {}, { auth: false }),
  /** 轮询票据状态（公开端点，契约 API②；1.5s 间隔只读轮询，GET 不消费票；
   *  status=confirmed 时 token 非空（确认者 AdminTokenIssued），终态后由调用方停止轮询）。 */
  qrPoll: (ticketId: string): Promise<QrTicketStatusView> =>
    apiFetch<QrTicketStatusView>(
      `/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}`,
      { method: 'GET' },
      { auth: false },
    ),
  /** 扫码取信息（认证端点，契约 API③；调用方持 B 端令牌（APP 员工会话），
   *  status created→scanned 幂等；expired/cancelled → 业务失败码）。 */
  qrInfo: (ticketId: string): Promise<QrTicketInfoView> =>
    apiFetch<QrTicketInfoView>(
      `/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}/info`,
      { method: 'GET' },
    ),
  /** 扫码确认（认证端点，契约 API④；B 端令牌，原子单次消费，成功返回确认者 AdminTokenIssued
   *  并互踢旧 B 会话；非 confirmed/cancelled/expired 均可确认）。 */
  qrConfirm: (ticketId: string): Promise<TokenIssued> =>
    apiJson<TokenIssued>(`/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}/confirm`, 'POST', {}),
  /** 扫码取消（认证端点，契约 API⑤；B 端令牌，幂等；void 端点 2xx 空体=成功）。 */
  qrCancel: (ticketId: string): Promise<void> =>
    apiJson<void>(`/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}/cancel`, 'POST', {}, { emptyAsSuccess: true }),
  /** 登出（需认证；当前会话即刻失效，已失效令牌幂等成功；void 端点 2xx 空体=成功）。 */
  logout: (): Promise<void> =>
    apiFetch<void>('/admin/v1/auth/tokens/current', { method: 'DELETE' }, { emptyAsSuccess: true }),
  // ————— 自助账户安全（REQ-20261119-082531；全部免权限点，仅本人） —————
  /** 自助改密（契约③）：成功返回 Result<Void> 且当前会话保持（响应头令牌不变），其他会话全部
   *  KICKED+撤销；旧密码错误 → OLD_PASSWORD_INVALID，新旧相同 → SAME_PASSWORD。 */
  selfPassword: (payload: SelfPasswordPayload): Promise<void> =>
    apiJson<void>('/admin/v1/auth/me/password', 'PUT', payload),
  /** 本人在线会话列表（契约④）：tokenId/ip/issuedAt/lastActiveAt + current 标记。 */
  selfSessions: (): Promise<SelfSessionView[]> =>
    apiFetch<SelfSessionView[]>('/admin/v1/auth/me/sessions'),
  /** 下线本人指定会话（契约④；仅本人会话可删，非本人=NOT_FOUND 语义防探测；幂等成功）。 */
  selfKickSession: (sessionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/auth/me/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }, { emptyAsSuccess: true }),
  /** 下线本人其他会话（契约④；保留当前会话）。 */
  selfKickOthers: (): Promise<void> =>
    apiFetch<void>('/admin/v1/auth/me/sessions/others', { method: 'DELETE' }, { emptyAsSuccess: true }),
  /** 登录日志分页（权限点 auth:loginlog:list；username/userType/result/时间区间筛选；
   *  from/to 为 ISO-8601 时间串；signal 为可选取消信号）。 */
  loginLogs: (
    page: number,
    size: number,
    filters: LoginLogFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<AdminLoginLogView>> =>
    apiFetch<PageResult<AdminLoginLogView>>(
      `/admin/v1/auth/login-logs?${buildQuery({ page, size, username: filters.username, userType: filters.userType, result: filters.result, from: filters.from, to: filters.to })}`,
      { signal },
    ),
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
    const query = buildQuery({ username: filters.username, userType: filters.userType, result: filters.result, from: filters.from, to: filters.to });
    return query ? `/admin/v1/auth/login-logs/export?${query}` : '/admin/v1/auth/login-logs/export';
  },
};
