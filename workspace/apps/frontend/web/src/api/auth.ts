import { apiFetch } from './client';

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

/** B 端登录提交（契约对齐 AdminLoginRequest）。 */
export interface LoginPayload {
  tenantCode: string;
  username: string;
  password: string;
  captchaId: string;
  captchaCode: string;
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

/** 登录域接口（认证域契约：图形验证码 / 租户目录 / 登录 / 登出）。 */
export const authApi = {
  /** 启用租户清单（公开端点；登录页可搜索下拉数据源）。 */
  tenants: (): Promise<TenantSummary[]> =>
    apiFetch<TenantSummary[]>('/admin/v1/auth/tenants', { method: 'GET' }, { auth: false }),
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
  /** 登出（需认证；当前会话即刻失效，已失效令牌幂等成功）。 */
  logout: (): Promise<void> =>
    apiFetch<void>('/admin/v1/auth/tokens/current', { method: 'DELETE' }),
};
