/** @vitest-environment jsdom */
/**
 * REQ-20261122-102954（TENANT-3）AC-10：web msw handlers 租户域五端点契约同形
 * （list/tree/create/init/status）。表面=契约 frontmatter apis + 确认记录⑨。
 *
 * MSW 接入口径照 web/tests/REQ20261122023213-schedulerMswContract.test.ts 先例：
 * 复用交付 handlers（src/mocks/handlers，与 dev mock 共用），msw/node setupServer +
 * onUnhandledRequest:'error'；直接以 fetch 驱动（端点/信封为契约锚点，不经 core api 包装层）。
 *
 * 【AMB 假设（TL 裁决锚点）】
 *   - AMB-1：msw tenants fixtures 须含层级样例（至少一行 parentId 非空且 level≥2）——
 *     层级列/父下拉数据源依赖（确认⑩）；无则红灯=fixture 缺样例非断言错。
 *   - AMB-2：tree 根数组容忍裸数组或 records 包裹（core AdminTenantTreeNode 主口径=裸数组）。
 *   - AMB-3：POST create 的 msw 存储行 level 由 handler 依据 parentId 计算父+1；
 *     若仅透传，level 断言红灯=fixture 未对齐确认②（level 物化列口径）。
 *   - AMB-4：status 端点 msw 只翻转目标行自身 status（层级级联为后端语义，msw 不复刻）。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const BASE = '/admin/v1/system/tenants';

const server = setupServer(...handlers);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

// ————— 支撑（fetch 直驱 + 信封拆包，scheduler 范本口径） —————

interface Envelope {
  success: boolean;
  code: string;
  message: string | null;
  data: unknown;
}

const call = async (path: string, init?: RequestInit): Promise<{ res: Response; body: Envelope }> => {
  // 原生 fetch 不解析相对 URL；补 jsdom origin 使 msw 相对路径 handler 命中同 origin
  const res = await fetch(new URL(path, window.location.origin), {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-102954', ...init?.headers },
  });
  const text = await res.text();
  const body = text.trim()
    ? JSON.parse(text) as Envelope
    : { success: res.ok, code: res.ok ? '0' : 'UNKNOWN', message: null, data: null };
  return { res, body };
};

const getJson = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path);

const postJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'POST', body: JSON.stringify(payload) });

const putJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'PUT', body: JSON.stringify(payload) });

/** 分页 data 形状断言（{records,total}）。 */
const expectPageData = (data: unknown): { records: Array<Record<string, unknown>>; total: number } => {
  expect(data, '分页 data 应为对象').toBeTypeOf('object');
  const page = data as { records: Array<Record<string, unknown>>; total: number };
  expect(Array.isArray(page.records)).toBe(true);
  expect(typeof page.total).toBe('number');
  return page;
};

/** 唯一码（msm store 无删除端点——以唯一性隔离替代 teardown，scheduler 范本纪律）。 */
let seq = 0;
const uniqCode = (suffix: string): string => `req102954msw${Date.now()}${++seq}${suffix}`;

// ————— 五端点同形（AC-10） —————

describe('REQ20261122102954 msw 租户域五端点契约同形（AC-10）', () => {
  it('should_list_tenants_with_hierarchy_fields_when_list_queried', async () => {
    const { body } = await getJson(`${BASE}?page=1&size=50`);
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');
    const page = expectPageData(body.data);
    expect(page.records.length, 'fixture 至少含租户行').toBeGreaterThan(0);

    // 层级字段同形（确认⑨）：record 含 parentId/level
    for (const row of page.records) {
      expect('parentId' in row, `record(${String(row.tenantCode)}) 应含 parentId`).toBe(true);
      expect('level' in row, `record(${String(row.tenantCode)}) 应含 level`).toBe(true);
    }
    // 顶层行 level=1 / parentId 空（确认②：NULL=顶层，1=顶层）
    const topLevel = page.records.filter((row) => row.parentId == null);
    expect(topLevel.length, 'fixture 应含顶层租户行').toBeGreaterThan(0);
    for (const row of topLevel) {
      expect(Number(row.level), '顶层行 level=1').toBe(1);
    }
    // 层级样例（AMB-1）：至少一行子租户（parentId 非空且 level≥2）
    const child = page.records.find((row) => row.parentId != null && Number(row.level) >= 2);
    expect(child, 'msw fixture 应含至少一行子租户（parentId 非空且 level≥2，AMB-1）').toBeTruthy();
  });

  it('should_return_full_tree_with_status_level_package_when_tree_queried', async () => {
    const { body } = await getJson(`${BASE}/tree`);
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');

    const data = body.data as unknown;
    const roots: Array<Record<string, unknown>> = Array.isArray(data)
      ? data as Array<Record<string, unknown>>
      : (data as { records?: Array<Record<string, unknown>> }).records ?? [];
    expect(roots.length, 'tree 应返回根节点数组（AMB-2 双口径兼容）').toBeGreaterThan(0);

    const flatten = (nodes: Array<Record<string, unknown>>): Array<Record<string, unknown>> =>
      nodes.flatMap((node) => [
        node,
        ...flatten(Array.isArray(node.children) ? node.children as Array<Record<string, unknown>> : []),
      ]);
    const all = flatten(roots);
    expect(all.length, 'tree 节点总数应多于根数（含 children 嵌套子节点）').toBeGreaterThan(roots.length);

    // 节点形状（AC-6：含 status/level/套餐）
    for (const field of ['tenantCode', 'status', 'level', 'packageId']) {
      expect(field in all[0]!, `tree 节点应含 ${field}（AC-6 形状）`).toBe(true);
    }
    // 树形一致性：子节点 parentId 指向树内父节点且 level=父+1（确认②）
    const withParent = all.filter((node) => node.parentId != null);
    expect(withParent.length, 'tree 应含 parentId 非空的子节点').toBeGreaterThan(0);
    for (const node of withParent) {
      const parent = all.find((item) => String(item.id) === String(node.parentId));
      expect(parent, `子节点(${String(node.tenantCode)}) parentId 应指向树内父节点`).toBeTruthy();
      expect(Number(node.level), '子节点 level=父+1').toBe(Number(parent!.level) + 1);
    }
  });

  it('should_echo_parentId_and_compute_level_when_child_tenant_created', async () => {
    const page = expectPageData((await getJson(`${BASE}?page=1&size=50`)).body.data);
    const parent = page.records.find((row) => row.parentId == null) ?? page.records[0]!;

    const payload = { tenantCode: uniqCode('child'), tenantName: 'msw 子租户', parentId: parent.id };
    const { body } = await postJson(BASE, payload);
    expect(body.success, '创建子租户业务成功').toBe(true);
    expect(body.code).toBe('0');

    const created = body.data as Record<string, unknown>;
    expect(created, '创建应返回租户视图').toBeTruthy();
    expect(String(created.parentId), '创建回显 parentId=提交值（wire 字符串口径）').toBe(String(payload.parentId));

    // 列表回读：新行 parentId 落库 + level=父+1（AMB-3）
    const after = expectPageData((await getJson(`${BASE}?page=1&size=50`)).body.data);
    const row = after.records.find((item) => item.tenantCode === payload.tenantCode);
    expect(row, '创建行应回读').toBeTruthy();
    expect(String(row!.parentId)).toBe(String(payload.parentId));
    expect(Number(row!.level), '子租户 level=父+1（确认②物化列口径，AMB-3）')
      .toBe(Number(parent.level ?? 1) + 1);
  });

  it('should_init_child_tenant_with_admin_when_init_carries_parentId', async () => {
    const page = expectPageData((await getJson(`${BASE}?page=1&size=50`)).body.data);
    const parent = page.records.find((row) => row.parentId == null) ?? page.records[0]!;

    const adminUsername = `req102954mswadmin${Date.now()}${++seq}`;
    const payload = {
      tenantCode: uniqCode('initchild'),
      tenantName: 'msw 开通子租户',
      remark: null,
      adminUsername,
      adminNickname: '子租户管理员',
      parentId: parent.id,
    };
    const { body } = await postJson(`${BASE}/init`, payload);
    expect(body.success, '开通子租户业务成功').toBe(true);
    expect(body.code).toBe('0');

    // AdminTenantInitView 同形：tenantCode/adminUsername 回显
    const data = body.data as Record<string, unknown>;
    expect(data, '开通应返回 InitView').toBeTruthy();
    expect(data.tenantCode).toBe(payload.tenantCode);
    expect(data.adminUsername).toBe(adminUsername);

    // 列表回读：开通租户 parent 归属（AC-2 同形面）
    const after = expectPageData((await getJson(`${BASE}?page=1&size=50`)).body.data);
    const row = after.records.find((item) => item.tenantCode === payload.tenantCode);
    expect(row, '开通租户应回读').toBeTruthy();
    expect(String(row!.parentId), '开通租户 parent=提交 parentId').toBe(String(payload.parentId));
  });

  it('should_flip_status_when_status_endpoint_put', async () => {
    // 自建唯一行翻转状态（隔离纪律：不污染种子行；AMB-4 只断目标行自身）
    const payload = { tenantCode: uniqCode('status'), tenantName: 'msw 状态租户' };
    const createdBody = (await postJson(BASE, payload)).body;
    expect(createdBody.success, '前置创建应成功').toBe(true);
    const created = createdBody.data as Record<string, unknown>;
    expect(created.id, '创建返回 id').toBeTruthy();

    const { body } = await putJson(`${BASE}/${String(created.id)}/status`, { status: 'DISABLED' });
    expect(body.success, '启停专用端点业务成功').toBe(true);
    expect(body.code).toBe('0');

    const after = expectPageData((await getJson(`${BASE}?page=1&size=50`)).body.data);
    const row = after.records.find((item) => String(item.id) === String(created.id));
    expect(row, '目标行仍在列表').toBeTruthy();
    expect(row!.status, '状态迁移回读=DISABLED').toBe('DISABLED');
  });
});
