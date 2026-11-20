/** @vitest-environment jsdom */
/**
 * REQ-20261120-213603 菜单筛选——msw handler 语义测试（AC-14）。
 *
 * 契约口径（contracts/REQ-20261120-213603.md §AC-14）：mock 环境携带筛选参数查询菜单树时，
 * handler 按与后端相同的命中语义返回（命中节点∪其全部祖先、命中节点的子孙不自动带出、多条件 AND、
 * keyword 对 menuName/path/permissionCode 不区分大小写包含）；mock 数据补含停用与隐藏样例。
 *
 * MSW 接入口径（REQ20261114120103-mswMeStore.test.ts 先例）：
 *   - 复用项目交付的 handlers（src/mocks/handlers，与 dev mock 共用），不另写桩 handler——
 *     本用例即「交付的 handler + 交付的 mock 样例」的验收面；
 *   - msw/node setupServer + onUnhandledRequest: 'error' 钉死端点覆盖；
 *   - 断言为契约镜像 oracle：以全量树推导期望命中集（命中∪祖先）后与 handler 返回集精确比对，
 *     对样例内容零硬编码——样例改动不影响用例；「含停用与隐藏样例」由样例存在性断言钉住。
 *
 * 依赖（映射表注明）：mock 菜单样例的「停用＋隐藏」两行为 frontend 工人并行交付项
 * （mocks/data.ts REQ-20261120-213603 注释段）；样例未落地时用例 1 的存在性断言红灯。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { systemApi, useAuthStore } from '@acme/core';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

type MenuNode = {
  id: number | string;
  parentId: number | string;
  menuName: string;
  menuType: string;
  path: string | null;
  permissionCode: string | null;
  visible: boolean;
  status: string;
  sortOrder: number | string;
  children: MenuNode[];
};

type Filters = {
  keyword?: string;
  menuType?: 'DIRECTORY' | 'MENU' | 'BUTTON';
  status?: 'ENABLED' | 'DISABLED';
  visible?: boolean;
};

// filters 为 menuTree 第 4 个可选参数（core 交付口径：menuTree(page?, size?, signal?, filters?)）
const queryMenuTree = (filters?: Filters) =>
  systemApi.menuTree(undefined, undefined, undefined, filters) as unknown as Promise<MenuNode[]>;

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
    accessToken: 'token-213603',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
});

afterEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

// ————— 契约镜像 oracle：命中节点 ∪ 全部祖先（子孙不自动带出） —————
const idOf = (node: MenuNode): string => String(node.id);
const parentOf = (node: MenuNode): string => String(node.parentId);

const flatten = (nodes: MenuNode[], out: MenuNode[] = []): MenuNode[] => {
  for (const node of nodes) {
    out.push(node);
    flatten(node.children ?? [], out);
  }
  return out;
};

const containsIgnoreCase = (hay: string | null | undefined, needle: string): boolean =>
  typeof hay === 'string' && hay.toLowerCase().includes(needle.toLowerCase());

const matches = (node: MenuNode, filters: Filters): boolean =>
  (filters.keyword === undefined
    || containsIgnoreCase(node.menuName, filters.keyword)
    || containsIgnoreCase(node.path, filters.keyword)
    || containsIgnoreCase(node.permissionCode, filters.keyword))
  && (filters.menuType === undefined || node.menuType === filters.menuType)
  && (filters.status === undefined || node.status === filters.status)
  && (filters.visible === undefined || node.visible === filters.visible);

const expectedIds = (full: MenuNode[], filters: Filters): Set<string> => {
  const flat = flatten(full);
  const parentById = new Map(flat.map((node) => [idOf(node), parentOf(node)]));
  const kept = new Set<string>();
  for (const node of flat) {
    if (!matches(node, filters)) continue;
    kept.add(idOf(node));
    let parent = parentById.get(idOf(node));
    while (parent !== undefined && parent !== '0') {
      kept.add(parent);
      parent = parentById.get(parent);
    }
  }
  return kept;
};

const idsOf = (tree: MenuNode[]): Set<string> => new Set(flatten(tree).map(idOf));

const expectTree = async (filters: Filters) => {
  const full = await queryMenuTree();
  const filtered = await queryMenuTree(filters);
  expect(idsOf(filtered)).toEqual(expectedIds(full, filters));
  return filtered;
};

describe('REQ20261120213603 msw 菜单树筛选 handler（AC-14）', () => {
  it('should_return_full_tree_when_no_filter_and_deliver_disabled_hidden_samples', async () => {
    const tree = await queryMenuTree();
    expect(Array.isArray(tree)).toBe(true);
    const flat = flatten(tree);
    expect(flat.length).toBeGreaterThan(0);

    // mock 样例依赖（frontend 交付）：含停用与隐藏样例，否则筛选无可演示命中
    expect(flat.some((node) => node.status === 'DISABLED')).toBe(true);
    expect(flat.some((node) => node.visible === false)).toBe(true);

    // 全量树链完整性：每个非根节点的父都在树中
    const parentById = new Map(flat.map((node) => [idOf(node), parentOf(node)]));
    for (const node of flat) {
      const parent = parentOf(node);
      if (parent !== '0') {
        expect(parentById.has(parent), `节点 ${idOf(node)} 的父 ${parent} 在树中`).toBe(true);
      }
    }
    // 树序：同层 children 按 sortOrder 升序
    for (const node of flat) {
      const kids = node.children ?? [];
      for (let i = 1; i < kids.length; i++) {
        expect(Number(kids[i].sortOrder)).toBeGreaterThanOrEqual(Number(kids[i - 1].sortOrder));
      }
    }
  });

  it('should_filter_disabled_nodes_with_ancestor_chain_when_status_query', async () => {
    const filtered = await expectTree({ status: 'DISABLED' });
    // 结果树形状保持祖先链：每个非根节点的父都在返回集内
    const ids = idsOf(filtered);
    for (const node of flatten(filtered)) {
      const parent = parentOf(node);
      if (parent !== '0') {
        expect(ids.has(parent), `结果节点 ${idOf(node)} 的父 ${parent} 在结果中`).toBe(true);
      }
    }
  });

  it('should_filter_hidden_nodes_with_ancestor_chain_when_visible_false_query', async () => {
    await expectTree({ visible: false });
  });

  it('should_filter_button_nodes_with_ancestor_chain_when_menu_type_query', async () => {
    await expectTree({ menuType: 'BUTTON' });
  });

  it('should_match_keyword_case_insensitively_when_name_path_or_code_probed', async () => {
    const full = await queryMenuTree();
    const flat = flatten(full);
    // name 命中：以某节点全名作关键字（全名⊆自身 → 含该节点∪祖先）
    const named = flat.find((node) => node.menuName.length > 0)!;
    expect(idsOf(await queryMenuTree({ keyword: named.menuName })))
      .toEqual(expectedIds(full, { keyword: named.menuName }));
    // path 命中（大小写变体）：取有 path 的节点，关键字=大写化路径
    const withPath = flat.find((node) => typeof node.path === 'string' && node.path.length > 1);
    if (withPath) {
      const keyword = (withPath.path as string).toUpperCase();
      expect(idsOf(await queryMenuTree({ keyword }))).toEqual(expectedIds(full, { keyword }));
    }
    // permissionCode 命中（大小写变体）：取有码的节点，关键字=大写化码
    const withCode = flat.find((node) => typeof node.permissionCode === 'string' && node.permissionCode.length > 1);
    if (withCode) {
      const keyword = (withCode.permissionCode as string).toUpperCase();
      expect(idsOf(await queryMenuTree({ keyword }))).toEqual(expectedIds(full, { keyword }));
    }
  });

  it('should_return_empty_list_when_keyword_hits_nothing', async () => {
    const filtered = await queryMenuTree({ keyword: 'zzz-无命中-213603' });
    expect(Array.isArray(filtered)).toBe(true);
    expect(idsOf(filtered).size).toBe(0);
  });

  it('should_apply_and_semantics_when_combined_filters', async () => {
    // 多条件 AND（命中集随条件收紧；oracle 同口径比对）
    await expectTree({ menuType: 'MENU', status: 'DISABLED' });
    await expectTree({ menuType: 'MENU', status: 'DISABLED', visible: true });
    const full = await queryMenuTree();
    const hiddenNamed = flatten(full).find((node) => node.visible === false && node.menuName.length > 0);
    if (hiddenNamed) {
      await expectTree({ keyword: hiddenNamed.menuName, visible: false });
    }
  });
});
