/** @vitest-environment jsdom */
/**
 * REQ-20261114-071607 AC-18：useCrud headless 状态机单测（分页翻页 / 搜索筛选 / 权限点显隐流转）。
 *
 * 【假定 API】契约（§确认记录⑥、AC-18、变更清单前端节）只固定行为不固定签名；本用例按以下表面编写，
 * TL 裁决后若命名/形状有出入，仅需同步调整 core 导出或本文件（见交付含糊点清单 A）：
 *
 *   type CrudPageQuery   = { page: number; size: number; keyword?: string };  // page/size/keyword 口径同 files API
 *   type CrudPageData<T> = { list: T[]; total: number };
 *   type CrudAction = 'create' | 'update' | 'delete';
 *
 *   const state = useCrud<T>({
 *     fetchPage: (query: CrudPageQuery) => Promise<CrudPageData<T>>,  // 挂载即拉取第 1 页
 *     pageSize?: number,                                              // 缺省 10
 *     permissions?: string[],                                         // 当前用户权限点集合
 *     actionPermissions?: Partial<Record<CrudAction, string>>,        // 动作→权限点；未映射的动作一律视为无权限
 *   });
 *   state: { list, total, page, size, loading, can(action), goToPage(p), search(keyword) }
 *   - goToPage：按目标页拉取，total 取最新响应
 *   - search：合并 keyword 并回到第 1 页；再次 search 覆盖（而非拼接）旧 keyword
 *   - can：actionPermissions 指定的权限点 ∈ permissions 才可见（默认拒绝）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useCrud } from '../src';

interface Row {
  id: string;
  name: string;
}

const row = (id: string, name: string): Row => ({ id, name });
const pageData = (rows: Row[], total: number = rows.length) => ({ list: rows, total });

const fetchPageMock = vi.fn(async (query: { page: number; size: number; keyword?: string }) => {
  if (query.keyword) return pageData([row('k1', `hit-${query.keyword}`)]);
  if (query.page === 2) return pageData([row('3', '第二页-A')], 2);
  return pageData([row('1', '第一页-A'), row('2', '第一页-B')]);
});

const granted = ['infra:file:upload', 'infra:file:delete'];
const baseOptions = {
  fetchPage: fetchPageMock,
  permissions: granted,
  actionPermissions: { create: 'infra:file:upload', delete: 'infra:file:delete' },
};

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
};

beforeEach(() => {
  fetchPageMock.mockClear();
});

describe('REQ20261114071607 useCrud headless 状态机', () => {
  it('should_fetch_first_page_when_mounted', async () => {
    const { result } = renderHook(() => useCrud(baseOptions));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetchPageMock).toHaveBeenCalledTimes(1);
    expect(fetchPageMock.mock.calls[0]?.[0]).toEqual({ page: 1, size: 10 });
    expect(result.current.list).toEqual([row('1', '第一页-A'), row('2', '第一页-B')]);
    expect(result.current.total).toBe(2);
    expect(result.current.page).toBe(1);
    expect(result.current.size).toBe(10);
  });

  it('should_toggle_loading_when_page_request_pends_then_resolves', async () => {
    const d = deferred<{ list: Row[]; total: number }>();
    fetchPageMock.mockImplementationOnce(() => d.promise);
    const { result } = renderHook(() => useCrud(baseOptions));
    expect(result.current.loading).toBe(true);
    await act(async () => {
      d.resolve(pageData([row('9', '手工页')], 1));
    });
    expect(result.current.loading).toBe(false);
    expect(result.current.list).toEqual([row('9', '手工页')]);
  });

  it('should_request_and_render_target_page_when_go_to_page', async () => {
    const { result } = renderHook(() => useCrud(baseOptions));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => { result.current.goToPage(2); });
    await waitFor(() => expect(result.current.page).toBe(2));
    expect(fetchPageMock.mock.calls.at(-1)?.[0]).toEqual({ page: 2, size: 10 });
    await waitFor(() => expect(result.current.list).toEqual([row('3', '第二页-A')]));
    expect(result.current.total).toBe(2);
  });

  it('should_reset_to_first_page_with_keyword_when_search', async () => {
    const { result } = renderHook(() => useCrud(baseOptions));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => { result.current.goToPage(2); });
    await waitFor(() => expect(result.current.page).toBe(2));
    act(() => { result.current.search('admin'); });
    await waitFor(() => expect(result.current.page).toBe(1));
    expect(fetchPageMock.mock.calls.at(-1)?.[0]).toEqual({ page: 1, size: 10, keyword: 'admin' });
    await waitFor(() => expect(result.current.list).toEqual([row('k1', 'hit-admin')]));
    expect(result.current.total).toBe(1);
  });

  it('should_replace_previous_keyword_when_searching_again', async () => {
    const { result } = renderHook(() => useCrud(baseOptions));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => { result.current.search('alpha'); });
    await waitFor(() => expect(result.current.page).toBe(1));
    act(() => { result.current.search('beta'); });
    await waitFor(() => {
      expect(fetchPageMock.mock.calls.at(-1)?.[0]?.keyword).toBe('beta');
    });
    await waitFor(() => expect(result.current.list).toEqual([row('k1', 'hit-beta')]));
  });

  it('should_allow_action_only_when_permission_granted', () => {
    const { result } = renderHook(() => useCrud({
      ...baseOptions,
      permissions: ['infra:file:upload'],
    }));
    expect(result.current.can('create')).toBe(true);
    expect(result.current.can('delete')).toBe(false);
    expect(result.current.can('update')).toBe(false);
  });

  it('should_flip_action_visibility_when_permissions_change', async () => {
    const { result, rerender } = renderHook(
      (props: { permissions: string[] }) => useCrud({ ...baseOptions, permissions: props.permissions }),
      { initialProps: { permissions: granted } },
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.can('create')).toBe(true);
    expect(result.current.can('delete')).toBe(true);
    rerender({ permissions: [] });
    expect(result.current.can('create')).toBe(false);
    expect(result.current.can('delete')).toBe(false);
    rerender({ permissions: granted });
    expect(result.current.can('create')).toBe(true);
    expect(result.current.can('delete')).toBe(true);
  });
});
