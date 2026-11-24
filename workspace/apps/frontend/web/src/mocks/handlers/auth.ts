import { http } from 'msw';
import * as db from '../data';
import { CAPTCHA_IMAGE_DATA } from '../data';
import {
  bodyOf, empty, excelBinary, fail, ok, page, pagingOf, slicePage, urlOf,
} from '../utils';

/** 认证域 handler（login-tenant-select / login-guard-captcha / 登录 / 登出 / 滑动续约 / 登录日志 / 在线用户）。
 *  REQ-20261117-011514 增验证码策略公开端点；REQ-20261117-025200 增公开站点配置与超管租户切换；
 *  REQ-20261117-061017 增登录日志导出与在线用户按用户名批量下线；
 *  REQ-20261119-040838 增扫码登录票据三段 mock（创建/轮询状态推进/取消）。 */

/** 会话/登录日志内存态（fixtures 拷贝；批量下线直接改写集合，登出刷新页面恢复）。 */
const store = { loginLogs: [...db.loginLogs], onlineUsers: [...db.onlineUsers] };

/** 自助会话内存态（REQ-20261119-082531 契约④）：tokenId 即令牌 jti；current=当前请求会话。
 *  demo 种子含当前会话与一台其他设备；改密/下线其他只移除非 current 行。 */
const selfSessionStore = {
  password: 'admin123',
  sessions: [
    { tokenId: 'jti-self-current', ip: '10.0.0.8', issuedAt: '2026-11-19T01:00:00Z', lastActiveAt: '2026-11-19T02:30:00Z', current: true },
    { tokenId: 'jti-other-device', ip: '10.0.0.21', issuedAt: '2026-11-18T09:00:00Z', lastActiveAt: '2026-11-19T01:40:00Z', current: false },
  ] as { tokenId: string; ip: string; issuedAt: string; lastActiveAt: string; current: boolean }[],
};

/** mock 超管口径（与 me fixtures 对齐：admin = super_admin，批量下线跳过并计入 skipped）。 */
const SUPERADMIN_USERNAME = 'admin';

/** 扫码票据内存态（REQ-20261119-040838）：创建即登记，轮询推进/取消只改写内存票。 */
const qrTickets = new Map<string, { status: 'created' | 'scanned' | 'confirmed' | 'cancelled'; polls: number }>();
let qrTicketSeq = 1;

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
  // ————— 扫码登录票据（REQ-20261119-040838 三段 mock：创建 / 轮询状态推进 / 取消） —————
  // 内存票仓：创建→created；每次轮询推进 created→scanned→confirmed（demo 口径，真实推进由 APP
  // info/confirm 驱动）；取消→cancelled（后续轮询保持 cancelled）；未知/删除票 → expired+token=null。
  http.post('/admin/v1/auth/qr-tickets', async () => {
    const ticketId = `qr-mock-${qrTicketSeq}`;
    qrTicketSeq += 1;
    qrTickets.set(ticketId, { status: 'created', polls: 0 });
    return ok({
      ticketId,
      qrPayload: JSON.stringify({ t: ticketId }),
      expiresInSeconds: 120,
    });
  }),
  http.get('/admin/v1/auth/qr-tickets/:ticketId', ({ params }) => {
    const ticket = qrTickets.get(String(params.ticketId));
    if (!ticket) {
      return ok({ status: 'expired', token: null });
    }
    ticket.polls += 1;
    if (ticket.status === 'created' && ticket.polls >= 2) {
      ticket.status = 'scanned';
    }
    if (ticket.status === 'scanned' && ticket.polls >= 3) {
      ticket.status = 'confirmed';
    }
    if (ticket.status === 'confirmed') {
      return ok({
        status: 'confirmed',
        token: {
          accessToken: 'mock-access-token-qr',
          tokenType: 'Bearer',
          expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
          userId: '1001',
          username: 'admin',
          nickname: '超级管理员',
        },
      });
    }
    return ok({ status: ticket.status, token: null });
  }),
  http.post('/admin/v1/auth/qr-tickets/:ticketId/cancel', ({ params }) => {
    const ticket = qrTickets.get(String(params.ticketId));
    if (ticket) {
      ticket.status = 'cancelled';
    }
    return empty();
  }),
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
  // ————— 管理端重置用户密码（REQ-20261124-213008 契约①；权限点 auth:credential:manage） —————
  // upsert 凭据并撤销该用户全部活跃会话（mock 不持凭据仓，仅信封同形）；
  // 策略违反（空/超 64/演示触发 __POLICY__）→ PASSWORD_POLICY_VIOLATED
  http.put('/admin/v1/auth/credentials/users/:userId/password', async ({ request }) => {
    const body = await bodyOf(request);
    const password = typeof body.password === 'string' ? body.password : '';
    if (!password || password.length > 64 || password.includes('__POLICY__')) {
      return fail('PASSWORD_POLICY_VIOLATED', '密码不符合策略（1~64 位非空）', 400);
    }
    return ok(null);
  }),
  // ————— 自助账户安全（REQ-20261119-082531：改密 / 本人会话列表 / 逐条与批量下线） —————
  // 自助改密（契约③）：旧密码错误 → OLD_PASSWORD_INVALID；新旧相同 → SAME_PASSWORD；
  // 成功 → 其他会话 KICKED+撤销（当前会话保持）→ Result<Void>
  http.put('/admin/v1/auth/me/password', async ({ request }) => {
    const body = await bodyOf(request);
    const oldPassword = typeof body.oldPassword === 'string' ? body.oldPassword : '';
    const newPassword = typeof body.newPassword === 'string' ? body.newPassword : '';
    if (oldPassword !== selfSessionStore.password) {
      return fail('OLD_PASSWORD_INVALID', '当前密码不正确', 200);
    }
    if (newPassword === oldPassword) {
      return fail('SAME_PASSWORD', '新密码不能与当前密码相同', 200);
    }
    selfSessionStore.password = newPassword;
    selfSessionStore.sessions = selfSessionStore.sessions.filter((row) => row.current);
    return ok(null);
  }),
  // 本人在线会话（契约④）：current 标记由 mock 种子给定
  http.get('/admin/v1/auth/me/sessions', () => ok(selfSessionStore.sessions)),
  // 下线其他会话（契约④）：先于 :sessionId 注册（静态段优先），保留当前会话
  http.delete('/admin/v1/auth/me/sessions/others', () => {
    selfSessionStore.sessions = selfSessionStore.sessions.filter((row) => row.current);
    return empty();
  }),
  // 下线本人指定会话（契约④）：非本人/不存在=NOT_FOUND 语义防探测；当前会话不可自踢（保留）
  http.delete('/admin/v1/auth/me/sessions/:sessionId', ({ params }) => {
    const tokenId = String(params.sessionId);
    const target = selfSessionStore.sessions.find((row) => row.tokenId === tokenId);
    if (!target) {
      return fail('SESSION_NOT_FOUND', '会话不存在或已失效', 404);
    }
    selfSessionStore.sessions = selfSessionStore.sessions.filter((row) => row.tokenId !== tokenId);
    return empty();
  }),
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
