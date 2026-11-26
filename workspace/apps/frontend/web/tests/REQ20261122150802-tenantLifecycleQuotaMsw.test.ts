// @vitest-environment jsdom
/**
 * REQ-20261122-150802（TENANT-4）AC-7：web msm handlers 租户用量/配额/生命周期端点契约同形。
 * 表面=契约 frontmatter apis + 确认记录⑥⑦（GET usage / PUT quota / update 收 graceUntil / status）。
 *
 * MSW 接入口径照 web/tests/REQ20261122102954-tenantsMswContract.test.ts 先例：
 * 复用交付 handlers（src/mocks/handlers，与 dev mock 共用），msw/node setupServer +
 * onUnhandledRequest:'error'；直接以 fetch 驱动（端点/信封为契约锚点，不经 core api 包装层）。
 * msw 2.x 桩为字符串路径（'/admin/v1/system/tenants/:tenantId/usage'），请求走同源相对 URL。
 *
 * 种子锚点（src/mocks/data.ts，实现侧交付）：acme(1)=无套餐无覆盖→平台缺省；beta(2)=套餐 5GiB；
 * beta-south(3)=覆盖 3GiB（TENANT_OVERRIDE）；gamma(4)=宽限样例（usage 种子 1GiB/3 用户）；
 * delta(5)=过期样例；epsilon(6)=停用样例。四态样例日期钉死 2026 年内有效（fixture 交付口径）。
 *
 * 隔离/还原纪律（msm store 为文件级内存态，跨用例共享）：
 *   - 覆盖用例终态=清除覆盖（PUT null，回落套餐/缺省，兼 AC-2 mock 面清除回落语义）；
 *   - update 用例终态=graceUntil null（与种子一致）；status 用例翻回原态。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const BASE = '/admin/v1/system/tenants';

/** 平台缺省配额（msm data 侧 StorageProperties 缺省 1GiB 演示口径）。 */
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

// ————— 支撑（fetch 直驱 + 信封拆包，tenantsMswContract 范本口径） —————

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
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-150802', ...init?.headers },
  });
  const text = await res.text();
  const body = text.trim()
    ? JSON.parse(text) as Envelope
    : { success: res.ok, code: res.ok ? '0' : 'UNKNOWN', message: null, data: null };
  return { res, body };
};

const getJson = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path);

const putJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'PUT', body: JSON.stringify(payload) });

interface PageData {
  records: Array<Record<string, unknown>>;
  total: number;
}

const listPage = async (): Promise<PageData> => {
  const { body } = await getJson(`${BASE}?page=1&size=50`);
  expect(body.success, '租户 list 应业务成功').toBe(true);
  const page = body.data as PageData;
  expect(Array.isArray(page.records)).toBe(true);
  return page;
};

const rowByCode = (page: PageData, tenantCode: string): Record<string, unknown> => {
  const row = page.records.find((item) => item.tenantCode === tenantCode);
  expect(row, `list 应含租户行 ${tenantCode}`).toBeTruthy();
  return row as Record<string, unknown>;
};

interface UsageView {
  storageBytes: number;
  userCount: number;
  quotaStorageBytes: number;
  quotaSource: string;
}

const getUsage = async (tenantId: string): Promise<UsageView> => {
  const { body } = await getJson(`${BASE}/${tenantId}/usage`);
  expect(body.success, `usage(${tenantId}) 应业务成功：${JSON.stringify(body)}`).toBe(true);
  expect(body.code).toBe('0');
  const usage = body.data as unknown as UsageView;
  // 契约⑦ AdminTenantUsage 形状：storageBytes/userCount/quotaStorageBytes（解析有效值）+来源层级
  for (const field of ['storageBytes', 'userCount', 'quotaStorageBytes', 'quotaSource'] as const) {
    expect(field in usage, `usage 应含 ${field}（契约⑦形状）`).toBe(true);
  }
  expect(typeof usage.storageBytes).toBe('number');
  expect(typeof usage.userCount).toBe('number');
  expect(typeof usage.quotaStorageBytes).toBe('number');
  expect(typeof usage.quotaSource).toBe('string');
  return usage;
};

// ————— AC-7：usage / quota / update / status 端点同形 —————

describe('REQ20261122150802 msm 租户用量/配额/生命周期端点契约同形（AC-7）', () => {
  it('should_return_usage_with_three_level_quota_source_when_usage_queried', async () => {
    // 平台缺省：acme 无套餐无覆盖 → PLATFORM_DEFAULT 1GiB；用量=种子基线
    const acme = await getUsage('1');
    expect(acme.storageBytes).toBe(268_435_456);
    expect(acme.userCount).toBe(12);
    expect(acme.quotaStorageBytes).toBe(PLATFORM_DEFAULT);
    expect(acme.quotaSource).toBe('PLATFORM_DEFAULT');

    // 套餐默认：beta 绑定基础版（5GiB）→ PACKAGE
    const beta = await getUsage('2');
    expect(beta.quotaStorageBytes).toBe(PACKAGE_QUOTA);
    expect(beta.quotaSource).toBe('PACKAGE');

    // 租户覆盖最高级：beta-south 覆盖种子 3GiB → TENANT_OVERRIDE
    const betaSouth = await getUsage('3');
    expect(betaSouth.quotaStorageBytes).toBe(OVERRIDE_SEED);
    expect(betaSouth.quotaSource).toBe('TENANT_OVERRIDE');

    // 宽限样例（gamma）用量种子同形（用量端点对四态样例一视同仁——聚合与派生态正交）
    const gamma = await getUsage('4');
    expect(gamma.storageBytes).toBe(1_073_741_824);
    expect(gamma.userCount).toBe(3);
  });

  it('should_put_override_then_clear_and_fall_back_to_package_when_quota_put', async () => {
    // 前置：beta 基线=套餐默认（PACKAGE 5GiB）
    expect((await getUsage('2')).quotaSource).toBe('PACKAGE');

    // 覆盖：PUT quota 12345 → AC-20 信封 data=null（void 端点信封化，不再返回租户视图；
    // 解析值语义经 usage/list 双面断言承载，不弱化）
    const putBody = (await putJson(`${BASE}/2/quota`, { quotaStorageBytes: 12_345 })).body;
    expect(putBody.success, `PUT quota 覆盖应业务成功：${JSON.stringify(putBody)}`).toBe(true);
    expect(putBody.code).toBe('0');
    expect(putBody.data, 'AC-20：PUT quota 信封 data=null').toBeNull();

    // usage/list 双面同形：来源层级=TENANT_OVERRIDE、列表行解析值同步
    const overridden = await getUsage('2');
    expect(overridden.quotaStorageBytes).toBe(12_345);
    expect(overridden.quotaSource).toBe('TENANT_OVERRIDE');
    expect(Number(rowByCode(await listPage(), 'beta').quotaStorageBytes)).toBe(12_345);

    // 清除回落：PUT quota null → 信封 data=null（AC-20），回落套餐默认值/来源经 usage 双面断言
    const clearBody = (await putJson(`${BASE}/2/quota`, { quotaStorageBytes: null })).body;
    expect(clearBody.success, `PUT quota 清除应业务成功：${JSON.stringify(clearBody)}`).toBe(true);
    expect(clearBody.code).toBe('0');
    expect(clearBody.data, 'AC-20：清除 PUT 信封 data=null').toBeNull();
    const fallbackUsage = await getUsage('2');
    expect(fallbackUsage.quotaStorageBytes, '清除覆盖回落套餐默认值（确认③）').toBe(PACKAGE_QUOTA);
    expect(fallbackUsage.quotaSource, '清除后来源层级回落 PACKAGE').toBe('PACKAGE');

    // 非法值拒绝（信封 code≠0，mock 面同形）：负数 → PARAM_INVALID
    const invalid = (await putJson(`${BASE}/2/quota`, { quotaStorageBytes: -1 })).body;
    expect(invalid.success, '负数配额应业务拒绝').toBe(false);
    expect(invalid.code).not.toBe('0');
  });

  it('should_carry_grace_until_when_update_put_and_clear_to_null', async () => {
    // update 收 graceUntil（确认⑥⑦）：设置 → 视图回显；清除（null）→ 回落 null（恢复种子态）
    const setBody = (await putJson(`${BASE}/1`, {
      tenantName: 'ACME 总部',
      remark: null,
      contactName: null,
      contactPhone: null,
      expiresAt: null,
      graceUntil: '2027-06-30T00:00:00.000Z',
    })).body;
    expect(setBody.success, `update 设宽限应业务成功：${JSON.stringify(setBody)}`).toBe(true);
    const view = setBody.data as Record<string, unknown>;
    expect(view.graceUntil, 'update 后视图回显 graceUntil（确认⑦ AdminTenantView 扩展）')
      .toBe('2027-06-30T00:00:00.000Z');
    expect('quotaStorageBytes' in view, 'view 应含解析后有效配额（确认⑦ AdminTenantView 扩展）').toBe(true);

    // list 回读同形
    expect(rowByCode(await listPage(), 'acme').graceUntil).toBe('2027-06-30T00:00:00.000Z');

    // 清除宽限：graceUntil null → 回读 null（种子态还原）
    const clearBody = (await putJson(`${BASE}/1`, {
      tenantName: 'ACME 总部',
      remark: null,
      contactName: null,
      contactPhone: null,
      expiresAt: null,
      graceUntil: null,
    })).body;
    expect(clearBody.success, `update 清宽限应业务成功：${JSON.stringify(clearBody)}`).toBe(true);
    expect((clearBody.data as Record<string, unknown>).graceUntil).toBeNull();
    expect(rowByCode(await listPage(), 'acme').graceUntil).toBeNull();
  });

  it('should_flip_status_of_disabled_sample_and_restore_when_status_put', async () => {
    // 四态样例③ epsilon：种子停用态 → 启停端点翻转 → 还原（隔离纪律，不污染终态）
    expect(rowByCode(await listPage(), 'epsilon').status).toBe('DISABLED');

    const enable = (await putJson(`${BASE}/6/status`, { status: 'ENABLED' })).body;
    expect(enable.success, '启停端点（空体）应业务成功').toBe(true);
    expect(rowByCode(await listPage(), 'epsilon').status).toBe('ENABLED');

    const restore = (await putJson(`${BASE}/6/status`, { status: 'DISABLED' })).body;
    expect(restore.success, '还原停用应业务成功').toBe(true);
    expect(rowByCode(await listPage(), 'epsilon').status).toBe('DISABLED');
  });
});
