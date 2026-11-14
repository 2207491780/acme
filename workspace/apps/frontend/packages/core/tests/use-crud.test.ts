/**
 * @vitest-environment jsdom
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../src/http/client';
import { useCrud, type CrudController, type CrudPage } from '../src/use-crud';

// React 18 act 环境（在 RTL 之外直接使用 act 时必须显式声明）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

/** 极简 renderHook 替身（jsdom + createRoot + act，零第三方依赖）。 */
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

/** 逐页静态数据源（page×10 记录）。 */
function staticSource(total: number) {
  return vi.fn(({ page, size }: { page: number; size: number }) => {
    const start = (page - 1) * size;
    const records = Array.from({ length: Math.max(0, Math.min(size, total - start)) }, (_, i) => `item-${start + i + 1}`);
    return Promise.resolve<CrudPage<string>>({ records, total });
  });
}

interface PageQuery {
  page: number;
  size: number;
  keyword: string;
  filters: Record<string, string | undefined>;
}

describe('useCrud（headless CRUD 状态机）', () => {
  let harness: ReturnType<typeof setupHook<CrudController<string>>> | null = null;

  afterEach(() => {
    harness?.unmount();
    harness = null;
  });

  it('should_fetch_first_page_on_mount_and_support_pagination', async () => {
    const fetchPage = staticSource(25);
    harness = await setupHook(() => useCrud<string>({ fetchPage, pageSize: 10 }));
    await act(async () => {
      await flush();
    });
    expect(fetchPage).toHaveBeenCalledTimes(1);
    // 查询形状最小化：无关键字时不携带 keyword 键
    expect(fetchPage.mock.calls[0]?.[0]).toEqual({ page: 1, size: 10 });
    expect(harness.result.current.records).toHaveLength(10);
    expect(harness.result.current.total).toBe(25);
    expect(harness.result.current.loading).toBe(false);
    expect(harness.result.current.status).toBe('ready');

    act(() => { harness.result.current.gotoPage(2); });
    await act(async () => {
      await flush();
    });
    expect(fetchPage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, size: 10 }));
    expect(harness.result.current.page).toBe(2);
    expect(harness.result.current.records[0]).toBe('item-11');
  });

  it('should_reset_to_first_page_and_pass_keyword_on_search', async () => {
    const fetchPage = staticSource(25);
    harness = await setupHook(() => useCrud<string>({ fetchPage, pageSize: 10 }));
    await act(async () => {
      await flush();
    });
    act(() => { harness.result.current.gotoPage(3); });
    await act(async () => {
      await flush();
    });
    act(() => { harness.result.current.search('alice'); });
    await act(async () => {
      await flush();
    });
    const lastCall = fetchPage.mock.calls.at(-1)?.[0] as PageQuery;
    expect(lastCall.page).toBe(1);
    expect(lastCall.keyword).toBe('alice');
    expect(harness.result.current.page).toBe(1);
    expect(harness.result.current.keyword).toBe('alice');
  });

  it('should_reset_to_first_page_and_pass_filters_on_set_filter', async () => {
    const fetchPage = staticSource(5);
    harness = await setupHook(() =>
      useCrud<string, { status?: 'ENABLED' | 'DISABLED' }>({ fetchPage, filters: { status: undefined } }),
    );
    await act(async () => {
      await flush();
    });
    act(() => { harness.result.current.setFilter('status', 'DISABLED'); });
    await act(async () => {
      await flush();
    });
    const lastCall = fetchPage.mock.calls.at(-1)?.[0] as PageQuery;
    expect(lastCall.page).toBe(1);
    expect(lastCall.filters).toEqual({ status: 'DISABLED' });
    expect(harness.result.current.filters.status).toBe('DISABLED');
  });

  it('should_discard_stale_response_when_newer_query_finished', async () => {
    const resolvers: Array<(value: CrudPage<string>) => void> = [];
    const fetchPage = vi.fn(
      () =>
        new Promise<CrudPage<string>>((resolve) => {
          resolvers.push(resolve);
        }),
    );
    harness = await setupHook(() => useCrud<string>({ fetchPage }));
    await act(async () => {
      await flush();
    });
    act(() => { harness.result.current.gotoPage(2); });
    await act(async () => {
      await flush();
    });
    expect(resolvers).toHaveLength(2);
    // 第 2 页先返回，第 1 页（过期）后返回 → 过期响应必须被丢弃
    await act(async () => {
      resolvers[1]?.({ records: ['page-2'], total: 2 });
      await flush();
    });
    await act(async () => {
      resolvers[0]?.({ records: ['stale-page-1'], total: 99 });
      await flush();
    });
    expect(harness.result.current.records).toEqual(['page-2']);
    expect(harness.result.current.total).toBe(2);
    expect(harness.result.current.page).toBe(2);
    expect(harness.result.current.loading).toBe(false);
  });

  it('should_step_back_a_page_when_current_page_turns_empty', async () => {
    let total = 11;
    const fetchPage = vi.fn(({ page, size }: { page: number; size: number }) => {
      const start = (page - 1) * size;
      const count = Math.max(0, Math.min(size, total - start));
      return Promise.resolve<CrudPage<string>>({
        records: Array.from({ length: count }, (_, i) => `item-${start + i + 1}`),
        total,
      });
    });
    harness = await setupHook(() => useCrud<string>({ fetchPage, pageSize: 10 }));
    await act(async () => {
      await flush();
    });
    act(() => { harness.result.current.gotoPage(2); });
    await act(async () => {
      await flush();
    });
    expect(harness.result.current.records).toEqual(['item-11']);
    // 模拟末页唯一记录被删除：总条数减一，第 2 页变空
    total = 10;
    act(() => { harness.result.current.reload(); });
    await act(async () => {
      await flush();
    });
    expect(harness.result.current.page).toBe(1);
    expect(fetchPage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
    expect(harness.result.current.records).toHaveLength(10);
  });

  it('should_gate_actions_fail_closed_by_permissions', async () => {
    const fetchPage = staticSource(0);
    // 未提供 permissions（me 未加载）→ 一律 false
    harness = await setupHook(() => useCrud<string>({ fetchPage }));
    expect(harness.result.current.can('USER_CREATE')).toBe(false);
    expect(harness.result.current.can('USER_DELETE')).toBe(false);

    const withPerms = await setupHook(() => useCrud<string>({ fetchPage, permissions: ['USER_CREATE'] }));
    try {
      expect(withPerms.result.current.can('USER_CREATE')).toBe(true);
      expect(withPerms.result.current.can('USER_DELETE')).toBe(false);
    } finally {
      withPerms.unmount();
    }

    const withEmpty = await setupHook(() => useCrud<string>({ fetchPage, permissions: [] }));
    try {
      expect(withEmpty.result.current.can('USER_CREATE')).toBe(false);
    } finally {
      withEmpty.unmount();
    }
  });

  it('should_enter_error_state_and_recover_on_reload', async () => {
    let failing = true;
    const fetchPage = vi.fn(() =>
      failing
        ? Promise.reject(new ApiError(500, 'INTERNAL', '服务开小差了'))
        : Promise.resolve<CrudPage<string>>({ records: ['ok'], total: 1 }),
    );
    harness = await setupHook(() => useCrud<string>({ fetchPage, errorMessage: '清单加载失败' }));
    await act(async () => {
      await flush();
    });
    expect(harness.result.current.status).toBe('error');
    expect(harness.result.current.error).toBe('服务开小差了');
    expect(harness.result.current.loading).toBe(false);

    failing = false;
    act(() => { harness.result.current.reload(); });
    await act(async () => {
      await flush();
    });
    expect(harness.result.current.status).toBe('ready');
    expect(harness.result.current.error).toBeNull();
    expect(harness.result.current.records).toEqual(['ok']);
  });

  it('should_use_fallback_error_message_for_non_api_failures', async () => {
    const fetchPage = vi.fn(() => Promise.reject(new TypeError('boom')));
    harness = await setupHook(() => useCrud<string>({ fetchPage, errorMessage: '清单加载失败' }));
    await act(async () => {
      await flush();
    });
    expect(harness.result.current.error).toBe('清单加载失败');
  });

  it('should_refetch_target_page_or_current_page_on_reload', async () => {
    const fetchPage = staticSource(30);
    harness = await setupHook(() => useCrud<string>({ fetchPage, pageSize: 10 }));
    await act(async () => {
      await flush();
    });
    act(() => { harness.result.current.reload(3); });
    await act(async () => {
      await flush();
    });
    expect(fetchPage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3 }));
    act(() => { harness.result.current.reload(); }); // 同页重取（删除/编辑后的刷新语义）
    await act(async () => {
      await flush();
    });
    expect(fetchPage).toHaveBeenCalledTimes(3);
    expect(fetchPage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3 }));
  });

  it('should_not_fetch_on_mount_when_immediate_false_until_reloaded', async () => {
    const fetchPage = staticSource(1);
    harness = await setupHook(() => useCrud<string>({ fetchPage, immediate: false }));
    await act(async () => {
      await flush();
    });
    expect(fetchPage).not.toHaveBeenCalled();
    expect(harness.result.current.status).toBe('idle');

    act(() => { harness.result.current.reload(); });
    await act(async () => {
      await flush();
    });
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(harness.result.current.status).toBe('ready');
  });
});
