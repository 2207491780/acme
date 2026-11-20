/** @vitest-environment jsdom */
/**
 * REQ-20261121-054613 服务能力开关——msw handler 语义测试（AC-10）。
 *
 * 契约口径（contracts/REQ-20261121-054613.md §AC-10 / §确认记录④⑤⑥）：mock 环境下能力面与
 * 后端同语义——
 *   ① 能力目录 GET /admin/v1/system/capabilities：全量启用列表、id asc、码/名/状态样例存在
 *      （V25 种子锚点 1=platform:workflow 工作流 / 2=platform:scheduler 调度中心）；
 *   ② 套餐 create/update 随体 capabilities 全量替换（未知码 → TENANT_CAPABILITY_INVALID 14-0006）、
 *      detail 回传 capabilities；
 *   ③ me.capabilities 样例存在：本人租户 acme（未绑定）= 全部启用码
 *      （TL 钉死 ['platform:workflow','platform:scheduler']）；绑定套餐后=套餐能力码、
 *      套餐改配即时生效（读时实时，镜像后端 AC-5/AC-6 语义）。
 *
 * MSW 接入口径（REQ20261121022910-packagesMock.test.ts 先例）：复用项目交付的 handlers
 * （src/mocks/handlers，与 dev mock 共用）不另写桩；msw/node setupServer + onUnhandledRequest:
 * 'error' 钉死端点覆盖；业务失败信封经 ApiError 拆包后错误码精确断言。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { ApiError, systemApi, tenantPackageApi, useAuthStore, useMeStore } from '@acme/core';
import { handlers } from '../src/mocks/handlers';
import * as db from '../src/mocks/data';

vi.setConfig({ testTimeout: 20000 });

/** ApiError 拆包（业务失败信封 → 异常）：负例断言走错误码精确匹配（TL 钉死事实面）。 */
const expectApiError = async (invoke: () => Promise<unknown>, code: string): Promise<void> => {
  try {
    await invoke();
    throw new Error(`应抛出业务失败（${code}），实际成功返回`);
  } catch (ex: unknown) {
    expect(ex, `应抛 ApiError（${code}）`).toBeInstanceOf(ApiError);
    expect((ex as ApiError).code).toBe(code);
  }
};

/** 能力码集合比较（回传顺序归契约外细节，集合语义=全量替换口径）。 */
const sameSet = (actual: string[] | undefined | null, expected: string[]): void => {
  expect([...(actual ?? [])].sort()).toEqual([...expected].sort());
};

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

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useAuthStore.getState().setSession({
    accessToken: 'token-054613',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: db.ME_TENANT_ID,
  }, false);
});

afterEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

describe('REQ20261121054613 msw 能力 handler（AC-10）', () => {
  it('should_mock_capability_semantics_with_me_payload', async () => {
    // —— ① 能力目录：样例存在（V25 种子锚点），仅启用、id asc ——
    const catalog = await systemApi.capabilityList();
    expect(catalog.map((item) => item.capabilityCode))
      .toEqual(['platform:workflow', 'platform:scheduler']);
    expect(catalog.map((item) => item.capabilityName))
      .toEqual(['工作流', '调度中心']);
    catalog.forEach((item) => {
      expect(item.status, '目录样例仅启用行').toBe('ENABLED');
    });

    // —— ② 创建随体 capabilities → 详情回传一致（校验通过=合法码面） ——
    const created = await tenantPackageApi.create({
      packageName: '能力套餐it-054613', remark: null, menuIds: [],
      capabilities: ['platform:workflow', 'platform:scheduler'],
    });
    const detailAfterCreate = await tenantPackageApi.detail(created.id);
    sameSet(detailAfterCreate.capabilities, ['platform:workflow', 'platform:scheduler']);

    // —— ③ 更新全量替换（version 乐观锁随体）→ 详情回传新集 ——
    const beforeUpdate = await tenantPackageApi.detail(created.id);
    await tenantPackageApi.update(created.id, {
      packageName: '能力套餐it-054613', remark: null, menuIds: [],
      capabilities: ['platform:workflow'], version: beforeUpdate.version,
    });
    const detailAfterUpdate = await tenantPackageApi.detail(created.id);
    expect(detailAfterUpdate.capabilities, '全量替换：新集完全替代旧集')
      .toEqual(['platform:workflow']);

    // —— ④ 未知能力码 → TENANT_CAPABILITY_INVALID（wire 码 14-0006 契约钉死） ——
    await expectApiError(
      () => tenantPackageApi.create({
        packageName: '非法能力it-054613', remark: null, menuIds: [],
        capabilities: ['platform:ghost-it-054613'],
      }),
      'TENANT_CAPABILITY_INVALID',
    );

    // —— ⑤ me.capabilities 样例存在：本人租户 acme 未绑定 → 全部启用码（TL 钉死） ——
    const meView = await systemApi.me();
    sameSet(meView.capabilities, ['platform:workflow', 'platform:scheduler']);

    // —— ⑥ meStore 透传（契约⑥：me.capabilities 进 core 类型与 meStore） ——
    await useMeStore.getState().load();
    sameSet(useMeStore.getState().me?.capabilities, ['platform:workflow', 'platform:scheduler']);

    // —— ⑦ 绑定套餐 → me.capabilities=套餐能力码（镜像后端 AC-5 绑定语义） ——
    const tenantPage = await systemApi.tenantList(1, 50);
    const meTenant = tenantPage.records.find((tenant) => tenant.id === db.ME_TENANT_ID);
    expect(meTenant, `本人租户 ${db.ME_TENANT_ID} 应在目录中`).toBeTruthy();
    await systemApi.tenantUpdate(db.ME_TENANT_ID, {
      tenantName: meTenant?.tenantName ?? 'ACME 总部',
      version: meTenant?.version ?? '0',
      packageId: Number(created.id),
    });
    expect((await systemApi.me()).capabilities, '绑定租户生效能力=套餐能力集')
      .toEqual(['platform:workflow']);

    // —— ⑧ 套餐改配即时生效（读时实时，无需重绑；镜像后端 AC-5 实时腿） ——
    const beforeRealtime = await tenantPackageApi.detail(created.id);
    await tenantPackageApi.update(created.id, {
      packageName: '能力套餐it-054613', remark: null, menuIds: [],
      capabilities: ['platform:scheduler'], version: beforeRealtime.version,
    });
    expect((await systemApi.me()).capabilities, '套餐改配后生效集即时切换')
      .toEqual(['platform:scheduler']);

    // —— ⑨ 解绑还原（packageId=null → 全部启用码；样例状态还原供复跑） ——
    const toUnbind = (await systemApi.tenantList(1, 50)).records
      .find((tenant) => tenant.id === db.ME_TENANT_ID);
    await systemApi.tenantUpdate(db.ME_TENANT_ID, {
      tenantName: toUnbind?.tenantName ?? 'ACME 总部',
      version: toUnbind?.version ?? '0',
      packageId: null,
    });
    sameSet((await systemApi.me()).capabilities, ['platform:workflow', 'platform:scheduler']);
  });
});
