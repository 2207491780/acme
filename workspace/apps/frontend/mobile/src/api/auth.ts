import { apiFetch, apiJson } from './client';
import { bAuthHeaders } from '../store/bSessionStore';

/** C 端登录域接口（契约对齐 AppAuthController；路径 /app/v1/auth）。
 *  REQ-20261119-040838 增 B 端员工账密登录（/admin/v1/auth）与扫码票据确认端点：
 *  B 端会话独立存于 bSessionStore（access-only），B 端认证端点经显式 Authorization 头携带
 *  B 令牌（apiFetch 的 auth:true 只认 C 端会话）。 */
export const authApi = {
  /** 发送短信验证码（公开端点；60s/手机号频控，命中返回 CAPTCHA_SEND_LIMITED；void 端点 2xx 空体=成功）。 */
  sendCaptcha: (phone: string): Promise<void> =>
    apiJson<void>('/app/v1/auth/captcha', 'POST', { phone }, { auth: false, emptyAsSuccess: true }),
  /** 验证码登录（公开端点；首登自动注册，签发双令牌 access 2h / refresh 14d）。 */
  login: (phone: string, captcha: string): Promise<AppTokenIssued> =>
    apiJson<AppTokenIssued>('/app/v1/auth/tokens', 'POST', { phone, captcha }, { auth: false }),
  /** 刷新轮换（公开端点自证；一次性轮换，旧对即用即废）。 */
  refresh: (refreshToken: string): Promise<AppTokenIssued> =>
    apiJson<AppTokenIssued>('/app/v1/auth/tokens/refresh', 'POST', { refreshToken }, { auth: false }),
  /** 登出（需认证；双令牌一并撤销，幂等成功；void 端点 2xx 空体=成功）。 */
  logout: (): Promise<void> =>
    apiFetch<void>('/app/v1/auth/tokens/current', { method: 'DELETE' }, { emptyAsSuccess: true }),
  // ————— B 端员工登录（REQ-20261119-040838，复用 /admin/v1/auth 既有端点） —————
  /** 租户验证码策略（公开端点；员工账密登录图形验证码显隐依据，策略查询失败 fail-closed 按开启）。 */
  adminCaptchaPolicy: (tenantCode: string): Promise<AdminCaptchaPolicyView> =>
    apiFetch<AdminCaptchaPolicyView>(
      `/admin/v1/auth/captcha-policy?tenantCode=${encodeURIComponent(tenantCode)}`,
      { method: 'GET' },
      { auth: false },
    ),
  /** 图形验证码（公开端点；imageBase64Data 为 data URI，RN Image 直接渲染；captchaId 登录时回传）。 */
  adminCaptcha: (): Promise<AdminCaptchaData> =>
    apiFetch<AdminCaptchaData>('/admin/v1/auth/captcha', { method: 'GET' }, { auth: false }),
  /** 员工账密登录（公开端点，契约对齐 POST /admin/v1/auth/tokens）：签发 B 端短会话令牌，
   *  写入 BSessionStore（acme.auth.bsession.v1）——不进主 Tabs（主 Tabs 仍属 C 端会话）。 */
  adminLogin: (payload: AdminLoginPayload): Promise<AdminTokenIssued> =>
    apiJson<AdminTokenIssued>('/admin/v1/auth/tokens', 'POST', payload, { auth: false }),
  // ————— 扫码票据确认（B 令牌认证端点；Authorization 经 bAuthHeaders 显式携带） —————
  /** 扫码取信息（status created→scanned 幂等；expired/cancelled → 业务失败码）。 */
  qrInfo: (ticketId: string): Promise<QrTicketInfoView> =>
    apiFetch<QrTicketInfoView>(
      `/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}/info`,
      { method: 'GET', headers: bAuthHeaders() },
    ),
  /** 扫码确认（原子单次；成功返回确认者 AdminTokenIssued 并互踢旧 B 会话）。 */
  qrConfirm: (ticketId: string): Promise<AdminTokenIssued> =>
    apiJson<AdminTokenIssued>(`/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}/confirm`, 'POST', {}, {
      extraHeaders: bAuthHeaders(),
    }),
  /** 扫码取消（幂等；void 端点 2xx 空体=成功）。 */
  qrCancel: (ticketId: string): Promise<void> =>
    apiJson<void>(`/admin/v1/auth/qr-tickets/${encodeURIComponent(ticketId)}/cancel`, 'POST', {}, {
      emptyAsSuccess: true,
      extraHeaders: bAuthHeaders(),
    }),
};

/** 令牌签发结果（契约对齐 AppTokenIssued；REQ-20261127-234158 wire golden 对齐——
 *  后端序列化为权威：C 端 wire 键为 accessExpiresAt/refreshExpiresAt（ISO-8601 UTC 字符串），
 *  不存在 expiresAt 键；userId 为 JSON 字符串（字符串化雪花 ID，防 JS Number 精度丢失）。 */
export interface AppTokenIssued {
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: string;
  refreshExpiresAt: string;
  userId: string;
  phone: string;
}

/** B 端登录提交（契约对齐 AdminLoginRequest；租户策略关时验证码字段整体省略）。 */
export interface AdminLoginPayload {
  tenantCode: string;
  username: string;
  password: string;
  captchaId?: string;
  captchaCode?: string;
}

/** B 端令牌签发结果（契约对齐 AdminTokenIssued；userId wire string；30 分钟短会话）。 */
export interface AdminTokenIssued {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  userId: string;
  username: string;
  nickname: string | null;
}

/** B 端图形验证码（契约对齐 CaptchaData；imageBase64Data 为 data:image/png;base64 data URI）。 */
export interface AdminCaptchaData {
  captchaId: string;
  imageBase64Data: string;
}

/** B 端验证码策略视图（契约对齐 CaptchaPolicyView）。 */
export interface AdminCaptchaPolicyView {
  captchaEnabled: boolean;
}

/** 扫码票据状态（契约 REQ-20261119-040838）。 */
export type QrTicketStatus = 'created' | 'scanned' | 'confirmed' | 'cancelled' | 'expired';

/** 扫码取信息视图（契约对齐 QrTicketInfoView；createdAt 为 ISO-8601）。 */
export interface QrTicketInfoView {
  status: QrTicketStatus;
  createdAt: string;
}
