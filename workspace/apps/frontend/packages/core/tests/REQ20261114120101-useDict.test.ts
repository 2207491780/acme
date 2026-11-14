/**
 * @vitest-environment jsdom
 *
 * REQ-20261114-120101 AC-2：useDict 缓存 hook——多组件共享单次拉取 + 显式刷新（core headless 单测）。
 *
 * 装配照既有范本 packages/core/tests/use-crud.test.ts（jsdom docblock + 极简 renderHook 替身 + act/flush）。
 * fetch 边界 mock 照 web/tests/meStore.test.ts（stubGlobal + Result 信封）。
 *
 * 【表面口径（round1 裁决⑥钉死，原 A-2/A-3 假定作废）】
 *   useDict(dictCode) 两步解析：
 *     1. GET /admin/v1/system/dict-types —— 返回类型清单（元素 = AdminDictTypeView 形状：
 *        id/dictCode/dictName/status/remark/createdAt/version，id 字符串），按 dictCode 匹配出 typeId；
 *     2. GET /admin/v1/system/dict-types/{typeId}/items?page&size —— 分页取全量
 *        （元素 = AdminDictItemView 形状：id/dictTypeId/itemLabel/itemValue/sortOrder/status/version）。
 *   缓存两层（code→typeId、code→items），refresh/invalidate 双层失效（契约⑥）。
 *
 * 【断言口径（round1 打回单钉死）】
 *   - fetch 计数两步各算一次：双挂载同 code 场景总请求数为 2（types 1 + items 1）而非 4；
 *   - 第二步请求必须携带第一步按 dictCode 匹配出的 typeId（路径含 /{typeId}/）；
 *   - 单次拉取 / 缓存共享 / refresh 重取 / 不同 code 各自拉取 四条语义断言保持。
 *
 * 【防串扰】缓存为模块级单例：各用例使用互不相同的 dictCode，避免跨用例命中既有缓存。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useDict } from '../src/use-dict';

// React 18 act 环境（在 RTL 之外直接使用 act 时必须显式声明）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.setConfig({ testTimeout: 25000 });

type DictType = {
  id: string; dictCode: string; dictName: string; status: 'ENABLED' | 'DISABLED';
  remark: string; createdAt: string; version: string;
};

type DictItem = {
  id: string; dictTypeId: string; itemLabel: string; itemValue: string;
  sortOrder: number; status: 'ENABLED' | 'DISABLED'; version: string;
};

const dictItem = (id: string, itemLabel: string, itemValue: string, sortOrder: number): DictItem => ({
  id, dictTypeId: '8801', itemLabel, itemValue, sortOrder, status: 'ENABLED', version: '0',
});

const ITEMS: DictItem[] = [
  dictItem('1', '启用', 'ENABLED', 1),
  dictItem('2', '停用', 'DISABLED', 2),
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

/** 极简 renderHook 替身（照抄 use-crud.test.ts，零第三方依赖）。 */
async function setupHook<A>(useHook: () => A): Promise<{ result: { current: A }; unmount: () => void }> {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  const result: { current: A } = { current: undefined as unknown as A };
  const Probe = () => {
    result.current = useHook();
    return null;
  };
  await act(async () => {
    root.render(createElement(Probe));
  });
  return {
    result,
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asUrl = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' || input instanceof URL
    ? new URL(String(input), 'http://localhost')
    : new URL((input as Request).url);
  return { url, method: (init?.method ?? 'GET').toUpperCase() };
};

/** 第一步：类型清单请求（GET /admin/v1/system/dict-types，弱匹配、不含 /items）。 */
const typesCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asUrl(input, init))
    .filter((r) => r.method === 'GET' && r.url.pathname.includes('/dict-types') && !r.url.pathname.includes('/items'));

/** 第二步：字典项请求（GET /admin/v1/system/dict-types/{typeId}/items，弱匹配）。 */
const itemsCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asUrl(input, init))
    .filter((r) => r.method === 'GET' && r.url.pathname.includes('/dict-types') && r.url.pathname.includes('/items'));

/** 每个 dictCode 生成一个类型 fixture（typeId 按序分配：8801、8802、…）。 */
const TYPE_ID_BASE = 8800;
const typeIdOf = (index: number) => String(TYPE_ID_BASE + index + 1);

/**
 * 两步形状 mock（裁决⑥）：
 * - 第一步 /dict-types：返回含全部目标 dictCode 的类型清单（含 id 供第二步匹配）；
 * - 第二步 /dict-types/{typeId}/items：返回 ITEMS（AdminDictItemView 形状）。
 */
const setupDictsApi = (codes: string[]) => {
  const types: DictType[] = codes.map((code, index) => ({
    id: typeIdOf(index),
    dictCode: code,
    dictName: `类型-${code}`,
    status: 'ENABLED',
    remark: '',
    createdAt: '2026-01-01T00:00:00Z',
    version: '0',
  }));
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asUrl(input, init);
    if (r.method !== 'GET' || !r.url.pathname.includes('/dict-types')) {
      return envelope(null);
    }
    if (r.url.pathname.includes('/items')) {
      return envelope({ records: ITEMS, total: ITEMS.length });
    }
    return envelope({ records: types, total: types.length });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('REQ20261114120101 useDict 缓存 hook（AC-2，两步形状·裁决⑥）', () => {
  it('should_fetch_once_when_two_components_mount_same_dict_type', async () => {
    const { fetchMock } = setupDictsApi(['dict-share-a']);
    const a = await setupHook(() => useDict('dict-share-a'));
    await act(async () => { await flush(); });
    const b = await setupHook(() => useDict('dict-share-a'));
    await act(async () => { await flush(); });

    // 多组件消费同一字典类型：两步各恰一次（总请求数=2 而非 4，缓存共享）
    expect(typesCallsOf(fetchMock)).toHaveLength(1);
    const items = itemsCallsOf(fetchMock);
    expect(items).toHaveLength(1);
    // 第二步携带第一步按 dictCode 匹配出的 typeId
    expect(items[0]?.url.pathname).toContain(`/${typeIdOf(0)}/`);
    // 字典项按 AdminDictItemView 口径暴露（itemLabel 展示 / itemValue 提交）
    expect(a.result.current.items).toHaveLength(2);
    expect(b.result.current.items).toHaveLength(2);
    expect(a.result.current.items.map((i) => i.itemValue)).toEqual(['ENABLED', 'DISABLED']);
    a.unmount();
    b.unmount();
  });

  it('should_not_refetch_when_remounted_after_unmount', async () => {
    const { fetchMock } = setupDictsApi(['dict-remount-b']);
    const first = await setupHook(() => useDict('dict-remount-b'));
    await act(async () => { await flush(); });
    first.unmount();

    const second = await setupHook(() => useDict('dict-remount-b'));
    await act(async () => { await flush(); });

    // 卸载后重挂仍命中两层缓存：两步各恰一次
    expect(typesCallsOf(fetchMock)).toHaveLength(1);
    const items = itemsCallsOf(fetchMock);
    expect(items).toHaveLength(1);
    expect(items[0]?.url.pathname).toContain(`/${typeIdOf(0)}/`);
    expect(second.result.current.items).toHaveLength(2);
    second.unmount();
  });

  it('should_refetch_when_explicit_refresh_called', async () => {
    const { fetchMock } = setupDictsApi(['dict-refresh-c']);
    const h = await setupHook(() => useDict('dict-refresh-c'));
    await act(async () => { await flush(); });
    expect(typesCallsOf(fetchMock)).toHaveLength(1);
    expect(itemsCallsOf(fetchMock)).toHaveLength(1);

    // 显式刷新：双层失效（code→typeId、code→items 均重取，契约⑥）→ 两步各再来一次
    await act(async () => { await h.result.current.refresh(); await flush(); });
    expect(typesCallsOf(fetchMock)).toHaveLength(2);
    const items = itemsCallsOf(fetchMock);
    expect(items).toHaveLength(2);
    expect(items[0]?.url.pathname).toContain(`/${typeIdOf(0)}/`);
    expect(items[1]?.url.pathname).toContain(`/${typeIdOf(0)}/`);
    expect(h.result.current.items).toHaveLength(2);
    h.unmount();
  });

  it('should_fetch_separately_per_dict_code_with_matched_type_id', async () => {
    const { fetchMock } = setupDictsApi(['dict-code-d1', 'dict-code-d2']);
    const a = await setupHook(() => useDict('dict-code-d1'));
    await act(async () => { await flush(); });
    const b = await setupHook(() => useDict('dict-code-d2'));
    await act(async () => { await flush(); });

    // 不同 code 各自拉取：各产生一次第二步请求，typeId 按 dictCode 各自匹配
    const items = itemsCallsOf(fetchMock);
    expect(items).toHaveLength(2);
    const itemPaths = items.map((c) => c.url.pathname);
    expect(itemPaths.some((p) => p.includes(`/${typeIdOf(0)}/`))).toBe(true);
    expect(itemPaths.some((p) => p.includes(`/${typeIdOf(1)}/`))).toBe(true);
    // 类型清单至少被拉取一次（是否跨 code 缓存不做断言）
    expect(typesCallsOf(fetchMock).length).toBeGreaterThanOrEqual(1);
    expect(a.result.current.items).toHaveLength(2);
    expect(b.result.current.items).toHaveLength(2);
    a.unmount();
    b.unmount();
  });
});
