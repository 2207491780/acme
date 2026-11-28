// @vitest-environment jsdom
/**
 * REQ-20261122-233426（TENANT-5）AC-7：web msm handlers 租户开通模板/运营视图/init 模板Code
 * 端点契约同形。表面=契约 frontmatter apis + 确认记录②③④⑥（tenant-templates 四端点 +
 * usage-overview + init 可选 templateCode）。
 *
 * MSW 接入口径照 web/tests/REQ20261122150802-tenantLifecycleQuotaMsw.test.ts 先例：
 * 复用交付 handlers（src/mocks/handlers，与 dev mock 共用），msw/node setupServer +
 * onUnhandledRequest:'error'；直接以 fetch 驱动（端点/信封为契约锚点，不经 core api 包装层）；
 * msw 2.x 桩为字符串路径，请求走同源相对 URL。
 *
 * 同形基准（委派单点 3：frontend 交付的 3 个契约未钉死点按先例钉死，以 msm 实现为基准断言）：
 *   - 模板审计列=createdAt+updatedAt（无 version 乐观锁列）；
 *   - packageDistribution 项={packageId, packageName, tenantCount}，未绑定聚合 packageId=null；
 *   - 重码=TENANT_TEMPLATE_DUPLICATE / content 非法=PARAM_INVALID / 不存在=TENANT_TEMPLATE_NOT_FOUND。
 *
 * 种子锚点（src/mocks/data.ts，实现侧交付）：tenantInitTemplates 2 条（801 startup-basic 前缀
 * 'startup-' + defaultPackageId '1'；802 minimal 无前缀无套餐）；tenants 6 条（acme/beta/beta-south/
 * gamma/delta/epsilon，四态样例日期钉死 2026 年内有效——TENANT-4 msm 同口径）；usage 种子
 * 1:268MiB/12 用户、2:96MiB/5、3:15MiB/2（3GiB TENANT_OVERRIDE）、4:1GiB/3、5:4MiB/1、6:0。
 *
 * 用例顺序=隔离纪律（msm store 为文件级内存态，跨用例共享）：
 *   ① overview 形状（纯读，种子基线断言）→ ② topN=20 截断（POST 25 租户，污染面后置）→
 *   ③ 模板 CRUD（自建自删，终态=种子还原）→ ④ init templateCode（租户/角色不可逆，最后执行）。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const BASE = '/admin/v1/system/tenants';
const TEMPLATES = '/admin/v1/system/tenant-templates';

/** 平台缺省配额（msm data 侧 StorageProperties 缺省 1GiB 演示口径，TENANT-4 msm 同源）。 */
const PLATFORM_DEFAULT = 1_073_741_824;
/** 套餐默认（data.ts 基础版 quotaStorageBytes=5GiB）。 */
const PACKAGE_QUOTA = 5_368_709_120;
/** beta-south 覆盖种子（data.ts 3GiB）。 */
const OVERRIDE_SEED = 3_221_225_472;

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

// ————— 支撑（fetch 直驱 + 信封拆包，TENANT-4 msm 范本口径） —————

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
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-233426', ...init?.headers },
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
const deleteJson = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path, { method: 'DELETE' });

interface PageData {
  records: Array<Record<string, unknown>>;
  total: number;
}

const expectOk = (body: Envelope, what: string): void => {
  expect(body.success, `${what} 应业务成功：${JSON.stringify(body)}`).toBe(true);
  expect(body.code, `${what} 成功信封 code=0`).toBe('0');
};

const recordsOf = (body: Envelope, what: string): Array<Record<string, unknown>> => {
  const data = body.data as PageData;
  if (Array.isArray(data?.records)) {
    return data.records;
  }
  expect(Array.isArray(data), `${what} 应返回列表或分页包裹`).toBe(true);
  return data as unknown as Array<Record<string, unknown>>;
};

const templateByCode = async (code: string): Promise<Record<string, unknown> | undefined> => {
  const { body } = await getJson(`${TEMPLATES}?page=1&size=50`);
  expectOk(body, '模板 list');
  return recordsOf(body, '模板 list').find((row) => row.code === code);
};

/** 合法模板载荷（确认② content 形状原文）。 */
const validPayload = (code: string, name: string): Record<string, unknown> => ({
  code,
  name,
  remark: 'msm 集成样例',
  content: {
    adminUsernamePrefix: 'it233426-',
    adminNickname: '集成管理员',
    rolePresets: [
      { roleKey: 'ops', roleName: '运维员', permissionCodes: ['system:user:list', 'system:user:create'] },
    ],
    configEntries: [{ configKey: 'notify.mail.enabled', value: 'true' }],
  },
  defaultPackageId: '1',
});

// ————— ① AC-7：usage-overview 顶层键/行键逐字=契约⑥ + 四态计数 + topN 形状 ----------

describe('REQ20261122233426 msm 运营视图端点契约同形（AC-7）', () => {
  it('should_return_usage_overview_with_contract_shape_when_queried', async () => {
    const { body } = await getJson(`${BASE}/usage-overview`);
    expectOk(body, 'usage-overview');
    const data = body.data as Record<string, unknown>;

    // 顶层键逐字=契约⑥ UsageOverviewView（totalTenants/statusCounts/packageDistribution/tenantUsages）
    expect(Object.keys(data).sort(), '顶层键逐字=契约⑥')
      .toEqual(['packageDistribution', 'statusCounts', 'tenantUsages', 'totalTenants']);
    expect(data.totalTenants, 'totalTenants=种子租户总数 6').toBe(6);

    // statusCounts 四键逐字（派生口径=页面四视觉态：DISABLED 正交优先→expires 未到=enabled→grace→expired；
    // 种子样例：enabled=acme/beta/beta-south、grace=gamma（expires 过 grace 未到）、expired=delta、disabled=epsilon）
    expect(Object.keys(data.statusCounts as Record<string, unknown>).sort())
      .toEqual(['disabled', 'enabled', 'expired', 'grace']);
    expect(data.statusCounts).toEqual({ enabled: 3, grace: 1, expired: 1, disabled: 1 });

    // tenantUsages 行键逐字=契约⑥原文（tenantId,tenantCode,tenantName,status,storageBytes,quotaBytes,quotaSource,userCount）
    const usages = data.tenantUsages as Array<Record<string, unknown>>;
    expect(Array.isArray(usages)).toBe(true);
    expect(usages.length, 'topN=20 上界内（种子 6 条全量）').toBe(6);
    for (const row of usages) {
      expect(Object.keys(row).sort(), '行键逐字=契约⑥原文')
        .toEqual(['quotaBytes', 'quotaSource', 'status', 'storageBytes', 'tenantCode', 'tenantId', 'tenantName', 'userCount']);
    }
    const storageValues = usages.map((row) => Number(row.storageBytes));
    expect([...storageValues].sort((a, b) => b - a), '按 storageBytes 降序（契约⑥）').toEqual(storageValues);

    // 行值=用量聚合源（种子基线）+ 配额三级解析（PLATFORM_DEFAULT/PACKAGE/TENANT_OVERRIDE）
    const byCode = (code: string): Record<string, unknown> => {
      const row = usages.find((item) => item.tenantCode === code);
      expect(row, `tenantUsages 应含 ${code}`).toBeTruthy();
      return row as Record<string, unknown>;
    };
    expect(Number(byCode('gamma').storageBytes)).toBe(1_073_741_824);
    expect(Number(byCode('acme').storageBytes)).toBe(268_435_456);
    expect(Number(byCode('acme').userCount)).toBe(12);
    expect(Number(byCode('acme').quotaBytes)).toBe(PLATFORM_DEFAULT);
    expect(byCode('acme').quotaSource).toBe('PLATFORM_DEFAULT');
    expect(Number(byCode('beta').quotaBytes)).toBe(PACKAGE_QUOTA);
    expect(byCode('beta').quotaSource).toBe('PACKAGE');
    expect(Number(byCode('beta-south').quotaBytes)).toBe(OVERRIDE_SEED);
    expect(byCode('beta-south').quotaSource).toBe('TENANT_OVERRIDE');

    // packageDistribution：项键={packageId, packageName, tenantCount}（委派点 3 钉死）；未绑定聚合 packageId=null
    const distribution = data.packageDistribution as Array<Record<string, unknown>>;
    expect(Array.isArray(distribution)).toBe(true);
    for (const row of distribution) {
      expect(Object.keys(row).sort(), 'packageDistribution 项键逐字')
        .toEqual(['packageName', 'packageId', 'tenantCount'].sort());
    }
    const unbound = distribution.find((row) => row.packageId === null);
    expect(unbound, '未绑定套餐应聚合为 packageId=null 组（确认⑥）').toBeTruthy();
    expect(Number(unbound?.tenantCount)).toBe(5);
    const bound = distribution.find((row) => row.packageId === '1');
    expect(bound, 'beta 绑定的基础版套餐应聚合').toBeTruthy();
    expect(Number(bound?.tenantCount)).toBe(1);
    expect(typeof bound?.packageName).toBe('string');
  });

  it('should_cap_tenant_usages_at_top_twenty_when_many_tenants', async () => {
    // 25 个简单创建租户（storage 0）→ 候选 31 → top20 截断；5 个有用量种子行必在前 20
    for (let i = 1; i <= 25; i += 1) {
      const created = (await postJson(`${BASE}`, {
        tenantCode: `it233426-top${i}`, tenantName: `TopN 样例 ${i}`,
      })).body;
      expectOk(created, `创建 topN 夹具租户 ${i}`);
    }
    const { body } = await getJson(`${BASE}/usage-overview`);
    expectOk(body, 'usage-overview');
    const usages = (body.data as Record<string, unknown>).tenantUsages as Array<Record<string, unknown>>;
    expect(usages.length, 'topN=20 截断（31 候选取前 20，契约⑥）').toBe(20);
    const storageValues = usages.map((row) => Number(row.storageBytes));
    expect([...storageValues].sort((a, b) => b - a)).toEqual(storageValues);
    for (const code of ['acme', 'beta', 'beta-south', 'gamma', 'delta']) {
      expect(usages.find((row) => row.tenantCode === code), `有用量种子的 ${code} 应保留在 top20`).toBeTruthy();
    }
  });
});

// ————— ② AC-7：tenant-templates 四端点同形（PARAM_INVALID 拒绝矩阵/重码/不存在） ----------

describe('REQ20261122233426 msm 租户开通模板端点契约同形（AC-7）', () => {
  it('should_pass_template_crud_with_rejection_matrix_when_handlers_driven', async () => {
    // ① list 种子形状：2 条样例；审计列=createdAt+updatedAt（无 version，委派点 3 钉死）；
    //    列表行自带完整 content（确认④无 detail 端点口径）
    const { body: listBody } = await getJson(`${TEMPLATES}?page=1&size=50`);
    expectOk(listBody, '模板 list');
    const seedRows = recordsOf(listBody, '模板 list');
    expect(seedRows.map((row) => row.code)).toEqual(expect.arrayContaining(['startup-basic', 'minimal']));
    for (const row of seedRows) {
      expect(Object.keys(row).sort(), '模板行审计列=createdAt+updatedAt，无 version（委派点 3）')
        .toEqual(['code', 'content', 'createdAt', 'defaultPackageId', 'id', 'name', 'remark', 'updatedAt']);
      expect(row.content, '列表行自带完整 content（无 detail 端点）').toBeTruthy();
    }
    const startup = seedRows.find((row) => row.code === 'startup-basic') as Record<string, unknown>;
    const startupContent = startup.content as Record<string, unknown>;
    expect(startupContent.adminUsernamePrefix).toBe('startup-');
    expect(Array.isArray(startupContent.rolePresets)).toBe(true);
    expect(Array.isArray(startupContent.configEntries)).toBe(true);

    // ② create 合法 → 回显 content/defaultPackageId/审计列
    const created = (await postJson(TEMPLATES, validPayload('it233426-crud', '集成样例模板'))).body;
    expectOk(created, '创建模板');
    const createdView = created.data as Record<string, unknown>;
    expect(typeof createdView.id === 'string' || typeof createdView.id === 'number').toBe(true);
    expect(createdView.code).toBe('it233426-crud');
    expect(createdView.defaultPackageId).toBe('1');
    expect((createdView.content as Record<string, unknown>).adminNickname).toBe('集成管理员');
    expect('createdAt' in createdView && 'updatedAt' in createdView).toBe(true);
    expect('version' in createdView, '模板视图无 version 乐观锁列（委派点 3）').toBe(false);

    // ③ 重码 → TENANT_TEMPLATE_DUPLICATE（委派点 3 钉死码）
    const duplicate = (await postJson(TEMPLATES, validPayload('startup-basic', '重码模板'))).body;
    expect(duplicate.success, '同码创建应业务拒绝').toBe(false);
    expect(duplicate.code).toBe('platform-system-15-0002');

    // ④ PARAM_INVALID 拒绝矩阵（确认③保存时即拒：保留前缀/空角色集/编码/名称）
    const invalidMatrix: Array<[string, Record<string, unknown>]> = [
      ['空角色集', { ...validPayload('it233426-empty', '空角色集模板'), content: { rolePresets: [], configEntries: [] } }],
      ['保留前缀 system:tenant:', {
        ...validPayload('it233426-rsv1', '保留前缀模板'),
        content: { rolePresets: [{ roleKey: 'ops', roleName: '运维员', permissionCodes: ['system:tenant:list'] }], configEntries: [] },
      }],
      ['保留前缀 system:tenant-package:', {
        ...validPayload('it233426-rsv2', '保留前缀模板'),
        content: { rolePresets: [{ roleKey: 'ops', roleName: '运维员', permissionCodes: ['system:tenant-package:list'] }], configEntries: [] },
      }],
      ['保留前缀 infra:audit:', {
        ...validPayload('it233426-rsv3', '保留前缀模板'),
        content: { rolePresets: [{ roleKey: 'ops', roleName: '运维员', permissionCodes: ['infra:audit:list'] }], configEntries: [] },
      }],
      ['编码非法（大写/下划线）', { ...validPayload('It233426_Bad', '非法编码模板') }],
      ['名称缺失', { ...validPayload('it233426-noname', ''), remark: null }],
    ];
    for (const [what, payload] of invalidMatrix) {
      const rejected = (await postJson(TEMPLATES, payload)).body;
      expect(rejected.success, `${what} 应业务拒绝：${JSON.stringify(rejected)}`).toBe(false);
      expect(rejected.code, `${what} 拒绝码=SYS-01-0001（REQ-20261128-051442 附录 B PARAM_INVALID 泛化映射）`).toBe('SYS-01-0001');
    }

    // ⑤ defaultPackageId 不存在 → 业务拒绝（存在性校验；码=TENANT_PACKAGE_NOT_FOUND 口径不断言字面量）
    const badPackage = (await postJson(TEMPLATES, {
      ...validPayload('it233426-badpkg', '非法套餐模板'), defaultPackageId: '999999',
    })).body;
    expect(badPackage.success, '非法 defaultPackageId 应业务拒绝').toBe(false);
    expect(badPackage.code).not.toBe('0');

    // ⑥ update 合法 → 名称/content 切换；list 同形
    const updated = (await putJson(`${TEMPLATES}/${String(createdView.id)}`, {
      ...validPayload('it233426-crud', '集成样例模板-改'),
      defaultPackageId: null,
    })).body;
    expectOk(updated, '更新模板');
    expect((updated.data as Record<string, unknown>).name).toBe('集成样例模板-改');
    const afterUpdate = await templateByCode('it233426-crud');
    expect(afterUpdate?.name).toBe('集成样例模板-改');
    expect(afterUpdate?.defaultPackageId ?? null).toBeNull();

    // ⑦ update 重码冲突（排除自身语义）+ 不存在
    const conflict = (await putJson(`${TEMPLATES}/${String(createdView.id)}`, {
      ...validPayload('minimal', '撞码模板'),
    })).body;
    expect(conflict.success).toBe(false);
    expect(conflict.code).toBe('platform-system-15-0002');
    const missing = (await putJson(`${TEMPLATES}/999999`, validPayload('it233426-x', '缺失模板'))).body;
    expect(missing.success).toBe(false);
    expect(missing.code).toBe('platform-system-15-0001');

    // ⑧ delete：空体 2xx + list 不再命中；重复删除 → TENANT_TEMPLATE_NOT_FOUND（终态=种子还原）
    const removed = await deleteJson(`${TEMPLATES}/${String(createdView.id)}`);
    expect(removed.res.status, '删除模板应 2xx（empty 空体）').toBe(200);
    expect(await templateByCode('it233426-crud'), '删除后 list 不再命中').toBeUndefined();
    const removedAgain = (await deleteJson(`${TEMPLATES}/999999`)).body;
    expect(removedAgain.success).toBe(false);
    expect(removedAgain.code).toBe('platform-system-15-0001');
  });
});

// ————— ③ AC-7：init templateCode 命中/未命中兼容（确认③） ----------

describe('REQ20261122233426 msm init 模板Code 端点契约同形（AC-7）', () => {
  it('should_apply_template_preset_when_init_with_template_code_and_fall_back_when_absent', async () => {
    // ① 命中模板：rolePresets 建角色（viewer 为种子外判别锚）+ 默认套餐兜底 + adminUsername 前缀合成
    const hit = (await postJson(`${BASE}/init`, {
      tenantCode: 'it233426-tpl', tenantName: '模板开通样例',
      adminUsername: 'boss', adminNickname: null, templateCode: 'startup-basic',
    })).body;
    expectOk(hit, 'init 命中模板');
    const hitView = hit.data as Record<string, unknown>;
    expect(hitView.tenantCode).toBe('it233426-tpl');
    expect(hitView.adminUsername, 'adminUsername=模板前缀+提交账号合成（确认③）').toBe('startup-boss');
    expect(hitView.packageId, '模板 defaultPackageId 兜底（确认③+默认套餐）').toBe('1');

    // 模板预置角色落 roles 面（viewer 为种子外判别锚；seed 仅 super_admin/ops）
    const rolesBody = (await getJson('/admin/v1/system/roles?page=1&size=100')).body;
    expectOk(rolesBody, 'roles list');
    const roleCodes = recordsOf(rolesBody, 'roles list').map((row) => row.roleCode);
    expect(roleCodes, '模板 rolePresets 应创建预置角色（ops/viewer）')
      .toEqual(expect.arrayContaining(['ops', 'viewer']));

    // ② 未命中 templateCode：回退现行为（不报错，动态 tenant_admin 角色，无套餐兜底）
    const miss = (await postJson(`${BASE}/init`, {
      tenantCode: 'it233426-miss', tenantName: '未命中样例',
      adminUsername: 'plain', templateCode: 'no-such-tpl-xyz',
    })).body;
    expectOk(miss, 'init 未命中模板（确认③兼容零回归）');
    const missView = miss.data as Record<string, unknown>;
    expect(missView.adminUsername, '未命中无前缀合成（现行为逐字不变）').toBe('plain');
    expect(missView.packageId ?? null, '未命中无默认套餐兜底').toBeNull();
    const rolesAfterMiss = recordsOf((await getJson('/admin/v1/system/roles?page=1&size=100')).body, 'roles list')
      .map((row) => row.roleCode);
    expect(rolesAfterMiss, '未命中路径建动态 tenant_admin 角色（契约⑤现行为）').toContain('tenant_admin');

    // ③ 未传 templateCode：同现行为（动态路径）
    const absent = (await postJson(`${BASE}/init`, {
      tenantCode: 'it233426-plain', tenantName: '无模板样例', adminUsername: 'plain2',
    })).body;
    expectOk(absent, 'init 未传模板');
    expect((absent.data as Record<string, unknown>).adminUsername).toBe('plain2');
  });
});
