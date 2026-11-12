import { apiFetch } from './client';

/** C 端登录域接口（契约对齐 AppAuthController；路径 /app/v1/auth）。 */
export const authApi = {
  /** 发送短信验证码（公开端点；60s/手机号频控，命中返回 CAPTCHA_SEND_LIMITED）。 */
  sendCaptcha: (phone: string): Promise<void> =>
    apiFetch<void>('/app/v1/auth/captcha', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phone }),
    }, { auth: false }),
  /** 验证码登录（公开端点；首登自动注册，签发双令牌 access 2h / refresh 14d）。 */
  login: (phone: string, captcha: string): Promise<AppTokenIssued> =>
    apiFetch<AppTokenIssued>('/app/v1/auth/tokens', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phone, captcha }),
    }, { auth: false }),
  /** 刷新轮换（公开端点自证；一次性轮换，旧对即用即废）。 */
  refresh: (refreshToken: string): Promise<AppTokenIssued> =>
    apiFetch<AppTokenIssued>('/app/v1/auth/tokens/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    }, { auth: false }),
  /** 登出（需认证；双令牌一并撤销，幂等成功）。 */
  logout: (): Promise<void> =>
    apiFetch<void>('/app/v1/auth/tokens/current', { method: 'DELETE' }),
};

/** 令牌签发结果（契约对齐 AppTokenIssued；expiresAt / refreshExpiresAt 为 ISO-8601）。 */
export interface AppTokenIssued {
  accessToken: string;
  refreshToken: string;
  expiresAt: string;
  refreshExpiresAt: string;
  userId: number;
  phone: string;
}
