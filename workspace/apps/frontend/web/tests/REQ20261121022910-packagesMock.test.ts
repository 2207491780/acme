/** @vitest-environment jsdom */
/**
 * REQ-20261121-022910 套餐——msw handler 语义测试（AC-14）。
 *
 * 契约口径（contracts/REQ-20261121-022910.md §AC-14）：mock 环境下套餐/租户携带筛选或绑定查询时，
 * handler 按与后端相同的语义返回——keyword 对 packageName 模糊（过滤）、menuCount/tenantCount 实时
 * 装配、业务错误码透出（TENANT_PACKAGE_DUPLICATE / NOT_FOUND / IN_USE / MENU_INVALID）、
 * 租户 packageId 绑定语义（undefined=不改动 / null=解除 / 非法→NOT_FOUND），
 * 并含「绑定套餐租户」样例（beta 租户绑基础版）演示租户套餐过滤（/me 菜单树实时过滤）。
 *
 * MSW 接入口径（REQ20261120213603-menuFilterMock.test.ts 先例）：
 *   - 复用项目交付的 handlers（src/mocks/handlers，与 dev mock 共用），不另写桩 handler——
 *     本用例即「交付的 handler + 交付的 mock 样例」的验收面；
 *   - msw/node setupServer + onUnhandledRequest: 'error' 钉死端点覆盖；
 *   - 断言对齐契约语义而非实现细节（TL 钉死口径）：错误码精确断言（wire 码契约钉死），
 *     /me 过滤用 oracle 全量推导比对（样例内容改动不影响用例）。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { ApiError, systemApi, tenantPackageApi, useAuthStore } from '@acme/core';
import { handlers } from '../src/mocks/handlers';
import * as db from '../src/mocks/data';

vi.setConfig({ testTimeout: 15000 });

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
    accessToken: 'token-022910',
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

// ————— 契约 oracle：套餐过滤语义（∩ 套餐菜单集 + 祖先补全 + BUTTON 随叶级联） —————

type MenuNode = {
  id: number | string;
  parentId: number | string;
  menuType: string;
  children: MenuNode[];
};

const flatten = (nodes: MenuNode[], out: MenuNode[] = []): MenuNode[] => {
  nodes.forEach((node) => {
    out.push(node);
    flatten(node.children ?? [], out);
  });
  return out;
};

/** 期望下发集 = 套餐菜单集 ∪ 套餐内节点的 BUTTON 直接子节点（随叶级联） ∪ 全部祖先（确认②）。 */
const expectedServedIds = (full: MenuNode[], packageIds: number[]): Set<string> => {
  const flat = flatten(full);
  const parentById = new Map(flat.map((node) => [String(node.id), String(node.parentId)]));
  const pkgSet = new Set(packageIds.map(String));
  const served = new Set<string>();
  for (const node of flat) {
    if (pkgSet.has(String(node.id))) {
      served.add(String(node.id));
      (node.children ?? []).forEach((child) => served.add(String(child.id)));
    }
  }
  for (const id of Array.from(served)) {
    let parent = parentById.get(id);
    while (parent !== undefined && parent !== '0') {
      served.add(parent);
      parent = parentById.get(parent);
    }
  }
  return served;
};

const idsOf = (tree: MenuNode[]): Set<string> => new Set(flatten(tree).map((node) => String(node.id)));

describe('REQ20261121022910 msw 套餐 handler（AC-14）', () => {
  it('should_mock_package_endpoints_with_same_semantics', async () => {
    // —— ① 分页：交付样例 ≥2 且含绑定租户样例（基础版被 beta 绑定 → tenantCount 实时=1） ——
    const page = await tenantPackageApi.page();
    expect(page.total).toBeGreaterThanOrEqual(2);
    const base = page.records.find((pkg) => pkg.packageName === '基础版');
    const free = page.records.find((pkg) => pkg.packageName === '全功能版');
    expect(base, '交付样例应含「基础版」（绑定租户演示样例）').toBeTruthy();
    expect(free, '交付样例应含「全功能版」（未绑定样例）').toBeTruthy();
    expect(base?.tenantCount, 'tenantCount 实时装配（beta 绑基础版）').toBe(1);
    expect(free?.tenantCount, '未绑定套餐 tenantCount=0').toBe(0);
    expect(base?.menuCount, 'menuCount 与交付绑定样例一致')
      .toBe(db.tenantPackageMenus[base?.id ?? '']?.length ?? -1);

    // —— ② keyword 过滤同语义（packageName 模糊）：命中样例名 → 仅该行；无命中 → 空页 ——
    const byKeyword = await tenantPackageApi.page({ keyword: '基础' });
    expect(byKeyword.total, 'keyword 命中 packageName 子串').toBe(1);
    expect(byKeyword.records[0]?.packageName).toBe('基础版');
    const miss = await tenantPackageApi.page({ keyword: 'zzz-无命中-022910' });
    expect(miss.total).toBe(0);

    // —— ③ 错误码透出（业务失败信封 code 精确断言；REQ-20261128-051442 附录 A.3 全码真值） ——
    await expectApiError(
      () => tenantPackageApi.create({ packageName: '基础版', remark: null, menuIds: [] }),
      'platform-system-14-0001',
    );
    await expectApiError(
      () => tenantPackageApi.create({ packageName: '非法套餐it', remark: null, menuIds: [999999999] }),
      'platform-system-14-0004',
    );
    await expectApiError(
      () => tenantPackageApi.detail('999999999'),
      'platform-system-14-0002',
    );
    await expectApiError(
      () => tenantPackageApi.remove(base?.id ?? '1'),
      'platform-system-14-0003',
    );

    // —— ④ 占用删除保留、空闲删除清除（删除后分页/绑定状态同语义联动） ——
    const freeId = free?.id ?? '2';
    await tenantPackageApi.remove(freeId);
    const afterDelete = await tenantPackageApi.page();
    expect(afterDelete.records.some((pkg) => pkg.id === freeId), '空闲套餐删除后消失').toBe(false);
    await expectApiError(() => tenantPackageApi.detail(freeId), 'platform-system-14-0002');
    expect((await tenantPackageApi.page()).records.find((pkg) => pkg.id === base?.id)?.tenantCount,
      '占用套餐不受他人删除影响').toBe(1);

    // —— ⑤ 租户绑定语义（PUT tenants）：undefined=不改动 / null=解除 / 非法→NOT_FOUND ——
    const boundTenantRow = db.tenants.find((tenant) => tenant.packageId !== null);
    const boundId = boundTenantRow?.id ?? '2';
    const fetchTenant = async () => {
      const list = await systemApi.tenantList(1, 50);
      const target = list.records.find((tenant) => tenant.id === boundId);
      expect(target, `租户 ${boundId} 应在目录中`).toBeTruthy();
      return target as NonNullable<typeof target>;
    };
    const before = await fetchTenant();
    // undefined=不改动既有绑定（向后兼容）
    const untouched = await systemApi.tenantUpdate(boundId, {
      tenantName: before.tenantName, remark: before.remark ?? undefined,
      contactName: before.contactName, contactPhone: before.contactPhone,
      expiresAt: before.expiresAt, version: before.version,
    });
    expect(untouched.packageId ?? null, '不传 packageId 不改动既有绑定').toBe(before.packageId ?? null);
    // 非法 packageId → platform-system-14-0002（TENANT_PACKAGE_NOT_FOUND）
    const afterTouch = await fetchTenant();
    await expectApiError(
      () => systemApi.tenantUpdate(boundId, {
        tenantName: afterTouch.tenantName, version: afterTouch.version, packageId: 999999999,
      }),
      'platform-system-14-0002',
    );
    // null=解除绑定
    const toUnbind = await fetchTenant();
    const unbound = await systemApi.tenantUpdate(boundId, {
      tenantName: toUnbind.tenantName, version: toUnbind.version, packageId: null,
    });
    expect(unbound.packageId ?? null, 'packageId=null 解除绑定').toBeNull();
    expect((await tenantPackageApi.page()).records.find((pkg) => pkg.id === base?.id)?.tenantCount,
      '解除绑定后 tenantCount 实时回零').toBe(0);

    // —— ⑥ 含绑定租户样例演示过滤：本人租户绑套餐 → /me 菜单树实时过滤（未绑=全量基线） ——
    const meBefore = (await systemApi.me()) as unknown as { menus: MenuNode[] };
    const fullIds = idsOf(meBefore.menus);
    expect(fullIds.size, '未绑定租户 /me 全量基线').toBeGreaterThan(0);

    // 绑定「套餐管理叶 46」（BUTTON 47~49 随叶级联）：/me 即时限定在套餐集∪级联∪祖先内
    const filterPkg = await tenantPackageApi.create({ packageName: '过滤演示it', remark: null, menuIds: [46] });
    const toBind = await systemApi.tenantList(1, 50);
    const meTenant = toBind.records.find((tenant) => tenant.id === db.ME_TENANT_ID);
    expect(meTenant, '本人租户应在目录中').toBeTruthy();
    await systemApi.tenantUpdate(db.ME_TENANT_ID, {
      tenantName: meTenant?.tenantName ?? 'ACME 总部',
      version: meTenant?.version ?? '0',
      packageId: Number(filterPkg.id),
    });
    const meAfter = (await systemApi.me()) as unknown as { menus: MenuNode[] };
    expect(idsOf(meAfter.menus),
      '绑定租户 /me 过滤=套餐集∪BUTTON 级联∪祖先（oracle 全等）')
      .toEqual(expectedServedIds(meBefore.menus, [46]));
    expect(idsOf(meAfter.menus).size, '过滤后树小于全量基线').toBeLessThan(fullIds.size);

    // 套餐变更即时生效（读时实时）：套餐增绑叶 11/12 → 下次读即出现，无需重绑
    //（round 2 追认：PUT 须携带乐观锁 version——取详情回显值随体提交）
    const beforeUpdate = await tenantPackageApi.detail(String(filterPkg.id));
    await tenantPackageApi.update(String(filterPkg.id), {
      packageName: '过滤演示it', menuIds: [11, 12], version: beforeUpdate.version,
    });
    const meAfterUpdate = (await systemApi.me()) as unknown as { menus: MenuNode[] };
    expect(idsOf(meAfterUpdate.menus),
      '套餐变更即时生效（无需重绑）')
      .toEqual(expectedServedIds(meBefore.menus, [11, 12]));
  });
});
