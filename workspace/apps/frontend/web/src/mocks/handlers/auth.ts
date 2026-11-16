import { http } from 'msw';
import { CAPTCHA_IMAGE_DATA } from '../data';
import { bodyOf, empty, fail, ok, urlOf } from '../utils';

/** 认证域 handler（login-tenant-select / login-guard-captcha / 登录 / 登出 / 滑动续约）。
 *  REQ-20261117-011514 增验证码策略公开端点。 */
export const authHandlers = [
  http.get('/admin/v1/auth/tenants', () => ok([
    { tenantCode: 'acme', tenantName: 'ACME 总部' },
    { tenantCode: 'beta', tenantName: 'Beta 试点' },
  ])),
  // 验证码策略（REQ-20261117-011514，公开端点）：mock 全租户默认关闭（与后端 V16 内置默认一致）；
  // tenantCode 空白 → 400 参数错误（契约口径）
  http.get('/admin/v1/auth/captcha-policy', ({ request }) => {
    const tenantCode = urlOf(request).searchParams.get('tenantCode') ?? '';
    if (!tenantCode.trim()) {
      return fail('PARAM_INVALID', 'tenantCode 不能为空', 400);
    }
    return ok({ captchaEnabled: false });
  }),
  http.get('/admin/v1/auth/captcha', () => ok({
    captchaId: 'captcha-mock-1',
    imageBase64Data: CAPTCHA_IMAGE_DATA,
  })),
  http.post('/admin/v1/auth/tokens', async ({ request }) => {
    const body = await bodyOf(request);
    const username = typeof body.username === 'string' && body.username.length > 0 ? body.username : 'admin';
    return ok({
      accessToken: 'mock-access-token',
      tokenType: 'Bearer',
      expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      userId: 1001,
      username,
      nickname: '超级管理员',
    });
  }),
  // 任意凭据可登录的 demo 口径：验证码留空校验仅在真实后端强制（mock 不阻断）
  http.post('/admin/v1/auth/tokens/refresh', () => ok({
    accessToken: 'mock-access-token-renewed',
    expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
  })),
  http.delete('/admin/v1/auth/tokens/current', () => empty()),
];
