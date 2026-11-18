import { http } from 'msw';
import * as db from '../data';
import { CAPTCHA_IMAGE_DATA } from '../data';
import {
  bodyOf, empty, excelBinary, fail, ok, page, pagingOf, slicePage, urlOf,
} from '../utils';

/** 认证域 handler（login-tenant-select / login-guard-captcha / 登录 / 登出 / 滑动续约 / 登录日志 / 在线用户）。
 *  REQ-20261117-011514 增验证码策略公开端点；REQ-20261117-025200 增公开站点配置与超管租户切换；
 *  REQ-20261117-061017 增登录日志导出与在线用户按用户名批量下线。 */

/** 会话/登录日志内存态（fixtures 拷贝；批量下线直接改写集合，登出刷新页面恢复）。 */
const store = { loginLogs: [...db.loginLogs], onlineUsers: [...db.onlineUsers] };

/** mock 超管口径（与 me fixtures 对齐：admin = super_admin，批量下线跳过并计入 skipped）。 */
const SUPERADMIN_USERNAME = 'admin';

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
  // 公开站点配置（REQ-20261117-025200，公开端点）：mock 默认 false/null（未配置不显示页脚）；
  // tenantCode 空白 → 400 参数错误（契约 AC-9 口径）
  http.get('/admin/v1/auth/public-config', ({ request }) => {
    const tenantCode = urlOf(request).searchParams.get('tenantCode') ?? '';
    if (!tenantCode.trim()) {
      return fail('PARAM_INVALID', 'tenantCode 不能为空', 400);
    }
    return ok({ footerCopyright: null, footerVisible: false });
  }),
  // 超管跨租户切换（REQ-20261117-025200，认证端点）：返回 AdminTokenIssued 形状（userId wire string）；
  // 切换即签新会话（旧会话互踢属预期）；目标租户缺失 → 400 参数错误
  http.post('/admin/v1/auth/tenant-switch', async ({ request }) => {
    const body = await bodyOf(request);
    const targetTenantCode = typeof body.targetTenantCode === 'string' ? body.targetTenantCode : '';
    if (!targetTenantCode.trim()) {
      return fail('PARAM_INVALID', 'targetTenantCode 不能为空', 400);
    }
    return ok({
      accessToken: 'mock-access-token-switched',
      tokenType: 'Bearer',
      expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      userId: '1001',
      username: 'admin',
      nickname: '超级管理员',
    });
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
      userId: '1001',
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
  // ————— 登录日志（REQ-20261114-120105 端点 + REQ-20261117-061017 导出） —————
  http.get('/admin/v1/auth/login-logs', ({ request }) => {
    const params = urlOf(request).searchParams;
    const rows = store.loginLogs.filter((row) => {
      if (params.get('username') && !row.username.includes(params.get('username')!)) return false;
      if (params.get('userType') && row.userType !== params.get('userType')) return false;
      if (params.get('result') && row.result !== params.get('result')) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  // export 先注册：与 login-logs 列表/其它子路径无段位冲突；xlsx 附件豁免信封
  http.get('/admin/v1/auth/login-logs/export', () => excelBinary('login-logs.xlsx')),
  // ————— 在线用户（REQ-20261114-120105 端点 + REQ-20261117-061017 批量下线） —————
  http.get('/admin/v1/auth/online-users', () => ok(store.onlineUsers)),
  // 批量下线（query 参数 username 必填）：枚举该用户全部 ACTIVE 未过期会话逐个 kick；
  // 超管会话跳过并计入 skippedCount（超管保护口径，契约确认⑥）
  http.delete('/admin/v1/auth/online-users', ({ request }) => {
    const username = urlOf(request).searchParams.get('username') ?? '';
    if (!username.trim()) {
      return fail('PARAM_INVALID', 'username 不能为空', 400);
    }
    const matching = store.onlineUsers.filter((row) => row.username === username);
    const kicked = matching.filter((row) => row.username !== SUPERADMIN_USERNAME).length;
    const skipped = matching.length - kicked;
    store.onlineUsers = store.onlineUsers.filter(
      (row) => !(row.username === username && row.username !== SUPERADMIN_USERNAME),
    );
    return ok({ kickedCount: kicked, skippedCount: skipped });
  }),
  // 单会话踢下线（路径参数 sessionId；目标不存在=SESSION_NOT_FOUND）
  http.delete('/admin/v1/auth/online-users/:sessionId', ({ params }) => {
    const sessionId = String(params.sessionId);
    const exists = store.onlineUsers.some((row) => row.sessionId === sessionId);
    if (!exists) {
      return fail('SESSION_NOT_FOUND', '目标会话不存在', 400);
    }
    store.onlineUsers = store.onlineUsers.filter((row) => row.sessionId !== sessionId);
    return empty();
  }),
];
