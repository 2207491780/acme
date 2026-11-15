/**
 * @vitest-environment jsdom
 *
 * REQ-20261114-120110 AC-5：mobile 字典消费——单次拉取缓存共享 + 字典缓存持久化。
 *
 * 断言目标=mobile 镜像 `src/use-dict.ts`（TL 裁决 AMB-4：core useDict 在 RN 直接复用
 * 前提不成立，frontend 落 mobile 镜像；语义对齐 core useDict——两步解析、双层缓存、
 * inflight 并发合并、useSyncExternalStore、refresh 双层失效 + 字典缓存持久化 AsyncStorage）。
 * 装配照抄范本 packages/core/tests/REQ20261114120101-useDict.test.ts
 * （jsdom docblock + 极简 renderHook 替身 + act/flush；fetch 边界 stubGlobal + Result 信封）。
 *
 * 【防串扰】缓存为模块级单例：各用例使用 REQ20261114120110 专属 dictCode 前缀；
 * beforeEach 显式 invalidateDicts 兜底。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { invalidateDicts, useDict } from '../src/use-dict';

// RN 宿主环境在 Node/vitest 下不可加载：AsyncStorage 以可读写内存桩替代（字典缓存持久化断言读取该桩）
const asyncMem = vi.hoisted(() => {
  const store = new Map<string, string>();
  const api = {
    getItem: vi.fn(async (key: string) => store.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn(async (key: string) => {
      store.delete(key);
    }),
  };
  return { store, api };
});
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: asyncMem.api,
  AsyncStorage: asyncMem.api,
}));

// 镜像 use-dict 经 mobile api client 间接依赖 SecureStore 会话（顶层 import 链），内存桩兜底
const secureMem = vi.hoisted(() => new Map<string, string>());
vi.mock('expo-secure-store', () => ({
  getItemAsync: vi.fn(async (key: string) => secureMem.get(key) ?? null),
  setItemAsync: vi.fn(async (key: string, value: string) => {
    secureMem.set(key, value);
  }),
  deleteItemAsync: vi.fn(async (key: string) => {
    secureMem.delete(key);
  }),
}));

// React 18 act 环境（在 RTL 之外直接使用 act 时必须显式声明）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type DictType = {
  id: string; dictCode: string; dictName: string; status: 'ENABLED' | 'DISABLED';
  remark: string; createdAt: string; version: string;
};

type DictItem = {
  id: string; dictTypeId: string; itemLabel: string; itemValue: string;
  sortOrder: number; status: 'ENABLED' | 'DISABLED'; version: string;
};

const dictItem = (id: string, itemLabel: string, itemValue: string, sortOrder: number): DictItem => ({
  id, dictTypeId: '12801', itemLabel, itemValue, sortOrder, status: 'ENABLED', version: '0',
});

const ITEMS: DictItem[] = [
  dictItem('1', '启用', 'ENABLED', 1),
  dictItem('2', '停用', 'DISABLED', 2),
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

/** 极简 renderHook 替身（照抄 core useDict 测试范本，零第三方依赖）。 */
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

/** 第一步：类型清单请求（GET /admin/v1/system/dict-types，不含 /items）。 */
const typesCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asUrl(input, init))
    .filter((r) => r.method === 'GET' && r.url.pathname.includes('/dict-types') && !r.url.pathname.includes('/items'));

/** 第二步：字典项请求（GET /admin/v1/system/dict-types/{typeId}/items）。 */
const itemsCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asUrl(input, init))
    .filter((r) => r.method === 'GET' && r.url.pathname.includes('/dict-types') && r.url.pathname.includes('/items'));

const TYPE_ID = '12801';

/** 两步形状 mock（裁决⑥口径，与 core 范本一致）。 */
const setupDictsApi = (codes: string[]) => {
  const types: DictType[] = codes.map((code) => ({
    id: TYPE_ID,
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

beforeEach(() => {
  asyncMem.store.clear();
  invalidateDicts();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('REQ20261114120110 mobile 镜像 useDict（AC-5，单次拉取缓存共享 + 持久化）', () => {
  it('should_fetch_once_when_two_screens_consume_same_dict_code', async () => {
    const { fetchMock } = setupDictsApi(['req120110-dict-share-a']);
    const a = await setupHook(() => useDict('req120110-dict-share-a'));
    await act(async () => { await flush(); });
    const b = await setupHook(() => useDict('req120110-dict-share-a'));
    await act(async () => { await flush(); });

    // mobile 两个 screen 消费同一字典类型：两步解析各恰一次（总请求数=2 而非 4，缓存共享）
    expect(typesCallsOf(fetchMock)).toHaveLength(1);
    const items = itemsCallsOf(fetchMock);
    expect(items).toHaveLength(1);
    // 第二步携带第一步按 dictCode 匹配出的 typeId
    expect(items[0]?.url.pathname).toContain(`/${TYPE_ID}/`);
    // 字典驱动 UI 数据面：itemLabel 展示 / itemValue 提交，两处消费拿到同一份条目
    expect(a.result.current.items).toHaveLength(2);
    expect(b.result.current.items).toHaveLength(2);
    expect(a.result.current.items.map((i) => i.itemValue)).toEqual(['ENABLED', 'DISABLED']);
    a.unmount();
    b.unmount();
  });

  it('should_not_refetch_when_screen_remounted_after_unmount', async () => {
    const { fetchMock } = setupDictsApi(['req120110-dict-remount-b']);
    const first = await setupHook(() => useDict('req120110-dict-remount-b'));
    await act(async () => { await flush(); });
    first.unmount();

    const second = await setupHook(() => useDict('req120110-dict-remount-b'));
    await act(async () => { await flush(); });

    // screen 卸载后重挂（Tab 切换场景）：命中双层缓存零请求
    expect(typesCallsOf(fetchMock)).toHaveLength(1);
    expect(itemsCallsOf(fetchMock)).toHaveLength(1);
    expect(second.result.current.items).toHaveLength(2);
    second.unmount();
  });

  it('should_persist_dict_cache_to_async_storage_after_fetch', async () => {
    setupDictsApi(['req120110-dict-persist-c']);
    const h = await setupHook(() => useDict('req120110-dict-persist-c'));
    await act(async () => { await flush(); });
    expect(h.result.current.items).toHaveLength(2);
    h.unmount();
    await flush();

    // 字典缓存持久化 AsyncStorage：桩中出现含该 dictCode 与条目数据的持久化记录
    // （不锁定 key 名：镜像实现自定命名，以值内容检索断言）
    const persistedValues = [...asyncMem.store.values()];
    expect(persistedValues.some((v) =>
      v.includes('req120110-dict-persist-c') && v.includes('ENABLED'),
    )).toBe(true);
  });
});
