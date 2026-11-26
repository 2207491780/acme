/**
 * REQ-20261125-230807 AC-4/AC-26 —— core api 提交侧雪花 ID 精度契约用例。
 *
 * 委派单钉死事实④口径：断言针对 packages/core/src/api/system.ts 导出的 api 函数
 * （>2^53 字符串 ID 传入后 fetch payload 原样字符串），fetch 桩拦截断言，不依赖 UI 渲染。
 *
 * 覆盖映射（页面半句见 web/tests/REQ20261125230807-*.test.tsx 各文件；映射总表见
 * WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-4  userCreate / userUpdate / userBindRoles / roleDataScope / roleUpdate(设父+清空哨兵 0)
 *         以 >2^53 字符串 ID 提交 → payload ID 原样字符串（无 Number 精度丢失）。
 *   AC-26 core 层代表性端点清扫：deptCreate/deptUpdate（DeptsPage 提交侧）/roleCreate/
 *         roleBindPermissions/menuUpdate(路径段)/tenantUpdate(路径段) 同口径钉死。
 *
 * 装配：node 环境（apiClient.test.ts 先例，client 在 node 最小 window 桩下可用）；
 * fetch 桩弱匹配 + envelope(Result code="0") 与范本 REQ20261124213008 同形。
 * 现状基线（契约背景段）：userBindRoles/roleDataScope/roleBindPermissions 提交侧 .map(Number)
 * —— 用例红=修复未落地的预期红，装配错误才算用例缺陷。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { systemApi } from '@acme/core';

vi.setConfig({ testTimeout: 30000 });

// > 2^53（9007199254740992）的雪花字符串 ID：Number() 化即丢精度（…807 → …6000）
const SF_USER = '9223372036854775807';
const SF_DEPT = '9223372036854775806';
const SF_DEPT_PARENT = '9223372036854775805';
const SF_POSITION = '9223372036854775804';
const SF_ROLE = '9223372036854775803';
const SF_ROLE_PARENT = '9223372036854775802';
const SF_PERMISSION = '9223372036854775801';
const SF_MENU = '9223372036854775800';
const SF_TENANT = '9223372036854775799';

// ————— fetch 桩（范本 helpers 口径） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

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

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> | null => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return null;
  }
};

const setupFetch = () => {
  const fetchMock = vi.fn(async () => envelope(null));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

afterEach(() => {
  vi.unstubAllGlobals();
});

// ————— AC-4：>2^53 雪花字符串 ID 提交 → payload 原样 —————
describe('REQ20261125230807 AC-4 core api 雪花 ID payload 不变形', () => {
  it('should_preserve_snowflake_string_ids_when_user_create_submitted', async () => {
    const fetchMock = setupFetch();
    await systemApi.userCreate({
      username: 'alice', nickname: '爱丽丝',
      roleIds: [SF_ROLE], deptId: SF_DEPT, positionId: SF_POSITION,
    });
    const posts = callsOf(fetchMock, 'POST', '/admin/v1/system/users');
    expect(posts, 'userCreate 应发出 POST /admin/v1/system/users').toHaveLength(1);
    const body = parseBody(posts[0]?.body) ?? {};
    expect(body.deptId, 'deptId 应为雪花字符串原样').toBe(SF_DEPT);
    expect(body.positionId, 'positionId 应为雪花字符串原样').toBe(SF_POSITION);
    expect(body.roleIds, 'roleIds 应为雪花字符串数组原样').toEqual([SF_ROLE]);
  });

  it('should_preserve_snowflake_string_ids_when_user_update_submitted', async () => {
    const fetchMock = setupFetch();
    await systemApi.userUpdate(SF_USER, {
      nickname: '爱丽丝', deptId: SF_DEPT, positionId: SF_POSITION, version: '0',
    });
    const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/users/${SF_USER}`);
    expect(puts, 'userUpdate 路径应携带雪花字符串 userId 原样').toHaveLength(1);
    expect(puts[0]?.url.pathname).toBe(`/admin/v1/system/users/${SF_USER}`);
    const body = parseBody(puts[0]?.body) ?? {};
    expect(body.deptId).toBe(SF_DEPT);
    expect(body.positionId).toBe(SF_POSITION);
  });

  it('should_preserve_snowflake_role_ids_when_user_bind_roles_submitted', async () => {
    const fetchMock = setupFetch();
    await systemApi.userBindRoles(SF_USER, [SF_ROLE]);
    const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/users/${SF_USER}/roles`);
    expect(puts).toHaveLength(1);
    const body = parseBody(puts[0]?.body) ?? {};
    expect(body.roleIds, 'roleIds 不得经 Number 数值化（现状 .map(Number) → 预期红）').toEqual([SF_ROLE]);
  });

  it('should_preserve_snowflake_dept_ids_when_role_data_scope_submitted', async () => {
    const fetchMock = setupFetch();
    await systemApi.roleDataScope(SF_ROLE, 'CUSTOM', [SF_DEPT, SF_DEPT_PARENT]);
    const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE}/data-scope`);
    expect(puts).toHaveLength(1);
    const body = parseBody(puts[0]?.body) ?? {};
    expect(body.deptIds, 'deptIds 不得经 Number 数值化（现状 .map(Number) → 预期红）').toEqual([SF_DEPT, SF_DEPT_PARENT]);
  });

  it('should_keep_parent_id_sentinel_and_string_parent_when_role_update_submitted', async () => {
    const fetchMock = setupFetch();
    // 清空父级 → 哨兵 0（三态契约：null=不调整、0=移为根、>0=改父；api 层不得把 0 吞成 null）
    await systemApi.roleUpdate(SF_ROLE, { roleName: '运维员', remark: '', version: '0', parentId: 0 });
    // 设父/改父 → 父角色雪花 ID 字符串原样
    await systemApi.roleUpdate(SF_ROLE, { roleName: '运维员', remark: '', version: '0', parentId: SF_ROLE_PARENT });
    const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE}`);
    expect(puts).toHaveLength(2);
    const clearBody = parseBody(puts[0]?.body) ?? {};
    expect(Number(clearBody.parentId), '清空父级应 wire parentId=0 哨兵').toBe(0);
    expect(clearBody.parentId, '哨兵 0 不得被改写为 null').not.toBeNull();
    const setBody = parseBody(puts[1]?.body) ?? {};
    expect(setBody.parentId, '设父应传雪花字符串原样（不得数值化）').toBe(SF_ROLE_PARENT);
  });
});

// ————— AC-26：core 层代表性端点清扫（含本契约未点名端点的同型提交侧） —————
describe('REQ20261125230807 AC-26 core api 代表性端点雪花精度清扫', () => {
  it('should_preserve_snowflake_string_ids_across_representative_endpoints', async () => {
    const fetchMock = setupFetch();

    // DeptsPage 提交侧：新建/改父（parentId 契约口径同角色三态）
    await systemApi.deptCreate({ parentId: SF_DEPT_PARENT, deptName: '平台组', sortOrder: 1 });
    await systemApi.deptUpdate(SF_DEPT, { parentId: SF_DEPT_PARENT, deptName: '平台组', sortOrder: 1, version: '0' });
    // roleCreate parentId、roleBindPermissions permissionIds（现状 .map(Number) → 预期红）
    await systemApi.roleCreate({ parentId: SF_ROLE_PARENT, roleCode: 'ops', roleName: '运维员' });
    await systemApi.roleBindPermissions(SF_ROLE, [SF_PERMISSION]);
    // 路径段 ID：menuUpdate(menuId)/tenantUpdate(tenantId) 路径原样字符串
    await systemApi.menuUpdate(SF_MENU as unknown as number, { menuName: '用户管理', sortOrder: 1, visible: true, version: '0' });
    await systemApi.tenantUpdate(SF_TENANT, { tenantName: '总部', version: '0' });

    const deptPosts = callsOf(fetchMock, 'POST', '/admin/v1/system/depts');
    expect(deptPosts).toHaveLength(1);
    expect((parseBody(deptPosts[0]?.body) ?? {}).parentId).toBe(SF_DEPT_PARENT);

    const deptPuts = callsOf(fetchMock, 'PUT', `/admin/v1/system/depts/${SF_DEPT}`);
    expect(deptPuts[0]?.url.pathname).toBe(`/admin/v1/system/depts/${SF_DEPT}`);
    expect((parseBody(deptPuts[0]?.body) ?? {}).parentId).toBe(SF_DEPT_PARENT);

    const rolePosts = callsOf(fetchMock, 'POST', '/admin/v1/system/roles');
    expect(rolePosts).toHaveLength(1);
    expect((parseBody(rolePosts[0]?.body) ?? {}).parentId).toBe(SF_ROLE_PARENT);

    const permPuts = callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE}/permissions`);
    expect((parseBody(permPuts[0]?.body) ?? {}).permissionIds,
      'permissionIds 不得经 Number 数值化（现状 .map(Number) → 预期红）').toEqual([SF_PERMISSION]);

    const menuPuts = callsOf(fetchMock, 'PUT', `/admin/v1/system/menus/${SF_MENU}`);
    expect(menuPuts[0]?.url.pathname).toBe(`/admin/v1/system/menus/${SF_MENU}`);

    const tenantPuts = callsOf(fetchMock, 'PUT', `/admin/v1/system/tenants/${SF_TENANT}`);
    expect(tenantPuts[0]?.url.pathname).toBe(`/admin/v1/system/tenants/${SF_TENANT}`);
  });
});
