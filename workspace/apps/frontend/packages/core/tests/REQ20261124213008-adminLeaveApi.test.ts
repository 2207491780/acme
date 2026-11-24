/** @vitest-environment jsdom */
/**
 * REQ-20261124-213008 前端补齐管理面功能——api 层契约用例（AC-1/AC-2/AC-4/AC-6/AC-9/AC-11/AC-12/AC-13 api 半句）。
 *
 * 页面交互半句见 web/tests/REQ20261124213008-adminFrontendPages.test.tsx；AC→用例映射见
 * WT/.ai-tmp/REQ-20261124-213008-tester-mapping.md。AC-15（mock 同形）按委派单落在 api+页面用例的自然覆盖上：
 * 本文件全部用例按统一响应体 Result 信封（success/code/message/data）+ 业务错误 code≠0 走 HTTP 200 承载，
 * 与后端契约同形，不单独建文件。
 *
 * 装配照 packages/core/tests/REQ20261114120103-apiFetch.test.ts：jsdom docblock（core barrel 拉 persist 型
 * store 需真实 localStorage）+ fetch stubGlobal + Result 信封；callsOf 弱匹配计数照钉死范本
 * web/tests/REQ20261117061017-loggingJobs.test.tsx；定时器卫生照 120103 教训（restoreAllMocks → useRealTimers）。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 新封装导出面 = core barrel '../src'：systemApi.userPasswordReset / systemApi.positionStatus /
 *       systemApi.roleTree / infraApi.fileGet / leaveApi.apply / leaveApi.get / workflowApi.get（确认②修正后表面）
 *       （契约 §变更清单命名 + 既有 api 对象先例 authApi/infraApi/systemApi）。
 *   A-2 路径型封装传参：路径参数独立传参（先例 infraApi.jobExecutions(key,page,size)），body 型参数按
 *       标量直传——自验 round1 实测口径：userPasswordReset(userId, password: string)、
 *       positionStatus(positionId, status: string)（按 DTO 对象传参会产出嵌套 body，实测红已就地修复）；
 *       leaveApi.apply(command) 按 LeaveApplyCommand 整体对象承载（实测透传）。
 *   A-3 payload 断言对 body 序列化形状鲁棒：method+path 精确断言；body 先做字符串包含断言，JSON 可解析时
 *       再补结构化断言（A-2 形状差异红则 TL 裁决，不静默降级）。
 *   A-4 业务错误=统一响应体 Result.code≠0（HTTP 200，勿断言 4xx）→ apiFetch 以错误收敛且 code 承载
 *       （REQ20261122023441 先例 ApiError(status, code, message)）。
 *   A-5 businessKey 生成位置（页面 or 封装）未契约化：api 层断言 command 透传（businessKey 原样入体），
 *       「LEAVE-{uuid} 前端生成」在页面用例的 wire body 上断言（两实现位置皆覆盖）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { infraApi, leaveApi, systemApi, workflowApi } from '../src';

vi.setConfig({ testTimeout: 15000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

// ————— fetch 边界 mock（范本 helpers：callsOf 弱匹配照抄 loggingJobs） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

/** body 结构化断言仅在 JSON 可解析时补做（A-3）。 */
const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> | null => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return null;
  }
};

// ————— fixtures（契约 §API 形状；id wire string） —————
const FILE_VIEW = {
  id: '9002', originalName: '合同扫描件.pdf', size: 1024, contentType: 'application/pdf',
  url: 'http://files.localhost/acme/files/2026/11/24/9002', bizType: 'demo', bizId: '42',
  createdAt: '2026-11-14T08:30:00Z',
};

const ROLE_TREE = [
  {
    id: '3101', parentId: null, roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED',
    builtIn: true, remark: '', createdAt: '2026-01-01T00:00:00Z', version: '0',
    children: [{
      id: '3103', parentId: '3101', roleCode: 'sec_officer', roleName: '安全员', status: 'ENABLED',
      builtIn: false, remark: '', createdAt: '2026-01-03T00:00:00Z', version: '0', children: [],
    }],
  },
  {
    id: '3102', parentId: null, roleCode: 'ops', roleName: '运维员', status: 'ENABLED',
    builtIn: false, remark: '', createdAt: '2026-01-02T00:00:00Z', version: '0', children: [],
  },
];

const DEF_VIEW = {
  id: '9002', key: 'expense-claim', name: '报销申请', version: '1', status: 'DEPLOYED',
  bpmnXml: null, layoutJson: null, createdAt: '2026-11-20T08:00:00Z', deployedAt: '2026-11-20T09:30:00Z',
};

const LEAVE_VIEW = {
  id: '8801', userName: 'admin', leaveType: '事假', reason: '家里有事',
  startTime: '2026-11-25T01:00:00Z', endTime: '2026-11-26T03:00:00Z',
  status: 'APPLYING', instanceId: null, businessKey: 'LEAVE-2f0c1a3e-9b7d-4c2a-8e5f-1a2b3c4d5e6f',
};

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
});

afterEach(() => {
  // 定时器卫生（120103 范本教训）：先还原探针再切回真实定时器，顺序不可反
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

// ————— AC-1/AC-2：管理端重置用户密码（api 半句） —————
describe('REQ20261124213008 重置用户密码 api（AC-1/AC-2）', () => {
  it('should_put_password_endpoint_with_password_payload_when_userPasswordReset_called', async () => {
    const fetchMock = vi.fn(async () => envelope(null));
    vi.stubGlobal('fetch', fetchMock);

    await systemApi.userPasswordReset('2001', 'Str0ng!Pass@2026');

    const puts = callsOf(fetchMock, 'PUT', '/admin/v1/auth/credentials/users/2001/password');
    expect(puts).toHaveLength(1);
    expect(puts[0]?.url.pathname).toBe('/admin/v1/auth/credentials/users/2001/password');
    // payload：密码字面量入体（A-3 形状鲁棒）
    expect(String(puts[0]?.body ?? '')).toContain('"password"');
    expect(String(puts[0]?.body ?? '')).toContain('Str0ng!Pass@2026');
    const parsed = parseBody(puts[0]?.body);
    if (parsed) expect(parsed.password).toBe('Str0ng!Pass@2026');
  });

  it('should_reject_with_policy_code_when_password_policy_violated', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => failureEnvelope('PASSWORD_POLICY_VIOLATED', '密码强度不符合策略')));

    // 业务错误=Result.code≠0（HTTP 200，A-4）：apiFetch 以错误收敛并携带 code
    await expect(systemApi.userPasswordReset('2001', 'weak'))
      .rejects.toMatchObject({ code: 'PASSWORD_POLICY_VIOLATED' });
  });
});

// ————— AC-4：文件详情（api 半句） —————
describe('REQ20261124213008 文件详情 api（AC-4）', () => {
  it('should_get_file_detail_endpoint_with_file_view_when_fileGet_called', async () => {
    const fetchMock = vi.fn(async () => envelope(FILE_VIEW));
    vi.stubGlobal('fetch', fetchMock);

    const view = await infraApi.fileGet('9002');

    expect(view.originalName).toBe('合同扫描件.pdf');
    const gets = callsOf(fetchMock, 'GET', '/admin/v1/infra/files/9002');
    expect(gets).toHaveLength(1);
    expect(gets[0]?.url.pathname).toBe('/admin/v1/infra/files/9002');
  });
});

// ————— AC-6：岗位启停（api 半句） —————
describe('REQ20261124213008 岗位启停 api（AC-6）', () => {
  it('should_put_position_status_endpoint_with_status_payload_when_positionStatus_called', async () => {
    const fetchMock = vi.fn(async () => envelope(null));
    vi.stubGlobal('fetch', fetchMock);

    await systemApi.positionStatus('5002', 'DISABLED');

    const puts = callsOf(fetchMock, 'PUT', '/admin/v1/system/positions/5002/status');
    expect(puts).toHaveLength(1);
    expect(puts[0]?.url.pathname).toBe('/admin/v1/system/positions/5002/status');
    // payload：status 字面量入体（A-3 形状鲁棒）
    expect(String(puts[0]?.body ?? '')).toContain('DISABLED');
    const parsed = parseBody(puts[0]?.body);
    if (parsed) expect(parsed.status).toBe('DISABLED');
  });
});

// ————— AC-9：角色树（api 半句） —————
describe('REQ20261124213008 角色树 api（AC-9）', () => {
  it('should_get_role_tree_endpoint_with_recursive_children_when_roleTree_called', async () => {
    const fetchMock = vi.fn(async () => envelope(ROLE_TREE));
    vi.stubGlobal('fetch', fetchMock);

    const tree = await systemApi.roleTree();

    const gets = callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree');
    expect(gets).toHaveLength(1);
    expect(gets[0]?.url.pathname).toBe('/admin/v1/system/roles/tree');
    // children 递归 + 根 parentId 归一 null（契约 §API 口径）
    expect(Array.isArray(tree)).toBe(true);
    const nodes = tree as Array<{ id: string; parentId: string | null; children: Array<{ id: string; roleCode: string }> }>;
    expect(nodes[0]?.parentId).toBeNull();
    expect(nodes[0]?.children?.[0]?.roleCode).toBe('sec_officer');
  });
});

// ————— AC-11：流程定义详情（api 半句；确认②修正后表面 workflowApi.get(definitionId)，barrel 无 definitions 键） —————
// TL 裁决（打回单 REQ-20261124-213008-tester round1）：round1 上报的表面冲突归因为契约确认②初稿别名过期——
// 真实封装名 = workflowApi.get(definitionId)，前端实现已按实况消费；本用例调用面已同步修正，断言不变。
describe('REQ20261124213008 流程定义详情 api（AC-11）', () => {
  it('should_get_definition_detail_endpoint_when_workflowApi_get_called', async () => {
    const fetchMock = vi.fn(async () => envelope(DEF_VIEW));
    vi.stubGlobal('fetch', fetchMock);

    const view = await workflowApi.get('9002');

    expect(view.key).toBe('expense-claim');
    const gets = callsOf(fetchMock, 'GET', '/admin/v1/workflow/process-definitions/9002');
    expect(gets).toHaveLength(1);
    expect(gets[0]?.url.pathname).toBe('/admin/v1/workflow/process-definitions/9002');
  });
});

// ————— AC-12：请假发起（api 半句；businessKey 生成位置未契约化，A-5） —————
describe('REQ20261124213008 请假发起 api（AC-12）', () => {
  it('should_post_leave_request_with_command_payload_when_leave_apply_called', async () => {
    const fetchMock = vi.fn(async () => envelope(LEAVE_VIEW));
    vi.stubGlobal('fetch', fetchMock);

    const command = {
      businessKey: 'LEAVE-2f0c1a3e-9b7d-4c2a-8e5f-1a2b3c4d5e6f',
      leaveType: '事假',
      reason: '家里有事',
      startTime: '2026-11-25T01:00:00Z',
      endTime: '2026-11-26T03:00:00Z',
    };
    await leaveApi.apply(command);

    const posts = callsOf(fetchMock, 'POST', '/admin/v1/leave/requests');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.url.pathname).toBe('/admin/v1/leave/requests');
    const body = parseBody(posts[0]?.body);
    expect(body).not.toBeNull();
    // LeaveApplyCommand 透传（A-5：businessKey 原样入体）
    expect(body?.businessKey).toBe(command.businessKey);
    expect(body?.leaveType).toBe('事假');
    expect(body?.reason).toBe('家里有事');
    // startTime/endTime 为 ISO-8601 Instant（契约 §API）
    expect(String(body?.startTime)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
    expect(String(body?.endTime)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
  });
});

// ————— AC-13：请假详情（api 半句） —————
describe('REQ20261124213008 请假详情 api（AC-13）', () => {
  it('should_get_leave_request_detail_endpoint_with_view_when_leave_get_called', async () => {
    const fetchMock = vi.fn(async () => envelope(LEAVE_VIEW));
    vi.stubGlobal('fetch', fetchMock);

    const view = await leaveApi.get('8801');

    expect(view.id).toBe('8801');
    expect(view.status).toBe('APPLYING');
    // 流程实例号可空（契约 §API：instanceId 可空）
    expect(view.instanceId).toBeNull();
    const gets = callsOf(fetchMock, 'GET', '/admin/v1/leave/requests/8801');
    expect(gets).toHaveLength(1);
    expect(gets[0]?.url.pathname).toBe('/admin/v1/leave/requests/8801');
  });
});
