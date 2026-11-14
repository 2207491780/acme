/**
 * REQ-20261114-120101 AC-3：DictSelect 字典选择器——受控 value/onChange、按字典项渲染选项、无权限不渲染。
 *
 * 装配照既有范本 web/tests/REQ20261114071607-usersPage.test.tsx：
 * 权限经 useMeStore 播种 + <AntApp> 包裹 + testTimeout ≥25000ms。
 *
 * 【REQ-20261114-120103 迁移留档（AC-7：既有样例迁移为 MSW 驱动）】
 *   本文件为 AC-7 指定的 MSW 迁移样例：fetch 边界 stubGlobal mock → msw/node server
 *   （handlers 复用 web/src/mocks 默认定义，用例内按需 server.use 覆盖 me/dict 两步）。
 *   原断言不变：选项渲染（启用/停用）、提交 itemValue（非 label）、受控回路回显、
 *   无权限不渲染（fail-closed）、字典消费两步序列各 ≥1 次请求；
 *   仅请求计数的数据源由 fetchMock.mock.calls 改为 resolver mock 的调用记录（ plumbing 变更，非断言变更）。
 *
 * 【表面口径（round1 已裁决部分钉死）】
 *   A-12 组件路径：web/src/components/DictSelect.tsx，命名导出 DictSelect
 *   A-13 props：受控 { value?: string; onChange?: (v: string) => void; dictCode: string }
 *   A-14 权限门禁：字典读权限（system:dict:list，三段式，裁决⑤）缺失 → 组件整体不渲染
 *   A-15 选项渲染：按 AdminDictItemView 渲染（itemLabel 展示、itemValue 提交）
 *   A-16 useDict 缓存为模块级单例：同文件用例使用互不相同的 dictCode 防跨用例命中缓存
 *
 * 【权限播种口径（round1 裁决⑤）】me.permissions = 后端三段式码（SystemPermissions.java 口径），
 * 双拼写播种已废除，统一只播三段式。
 *
 * 【字典消费面（round1 裁决⑥）】useDict 两步解析：
 *   GET /admin/v1/system/dict-types（按 dictCode 匹配 typeId）→ GET /admin/v1/system/dict-types/{typeId}/items。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { App as AntApp } from 'antd';
import { HttpResponse, http } from 'msw';
import { DictSelect } from '../src/components/DictSelect';
import { server } from '../src/mocks/server';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// MSW 生命周期：handler 覆盖本用例请求面（me + 字典两步），未覆盖请求显式红灯（照 mswMeStore 迁移口径）
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

const PERM_DICT_LIST = ['system:dict:list'];

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

const stubBrowserGaps = () => {
  // AntD5 在 jsdom 下的两处环境缺口
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

/** MSW resolver mock → 与原 fetchMock.mock.calls 同形的调用记录（[Request, { method }]）。 */
const callsOfResolver = (mock: { mock: { calls: unknown[][] } }): [Request, RequestInit][] =>
  (mock.mock.calls as { request: Request }[][]).map(([{ request }]) => [request, { method: request.method }]);

const seedMe = async (permissions: string[]) => {
  // me 播种经 MSW 覆盖（web/src/mocks 默认 handler 之上）并实际加载 me 画像；返回 resolver mock 备断言
  const meHandler = vi.fn(() => HttpResponse.json({
    success: true, code: '0', message: null,
    data: {
      user: {
        id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
        status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
      },
      roles: ['super_admin'],
      permissions,
      menus: [],
    },
  }, { status: 200 }));
  server.use(http.get('/admin/v1/system/me', meHandler));
  await useMeStore.getState().load();
  return meHandler;
};

/** 字典消费面两步 handler（裁决⑥）：/dict-types 按 dictCode 出类型（id=8801），/{typeId}/items 出字典项。
 *  resolver 为 vi.fn（调用记录供断言；MSW 请求入参 { request }）。 */
const setupDictsApi = (codes: string[]) => {
  const types: DictType[] = codes.map((code) => ({
    id: '8801',
    dictCode: code,
    dictName: `类型-${code}`,
    status: 'ENABLED',
    remark: '',
    createdAt: '2026-01-01T00:00:00Z',
    version: '0',
  }));
  const typesHandler = vi.fn(({ request: _request }: { request: Request }) => HttpResponse.json({
    success: true, code: '0', message: null,
    data: { records: types, total: types.length },
  }, { status: 200 }));
  const itemsHandler = vi.fn(() => HttpResponse.json({
    success: true, code: '0', message: null,
    data: { records: ITEMS, total: ITEMS.length },
  }, { status: 200 }));
  server.use(http.get('/admin/v1/system/dict-types', typesHandler));
  server.use(http.get('/admin/v1/system/dict-types/:typeId/items', itemsHandler));
  return { typesHandler, itemsHandler };
};

/** 受控探针：value/onChange 走真受控回路，onChange 调用序列留档断言。 */
const Harness = ({ dictCode, calls }: { dictCode: string; calls: string[] }) => {
  const [value, setValue] = useState<string>('');
  return (
    <AntApp>
      <DictSelect
        dictCode={dictCode}
        value={value}
        onChange={(next: string) => { calls.push(next); setValue(next); }}
      />
    </AntApp>
  );
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261114120101 DictSelect（AC-3，MSW 驱动迁移样例）', () => {
  it('should_render_options_from_dict_items_when_permitted', async () => {
    seedMe(PERM_DICT_LIST);
    const { typesHandler, itemsHandler } = setupDictsApi(['dict-select-render']);
    render(<Harness dictCode="dict-select-render" calls={[]} />);

    const combobox = await screen.findByRole('combobox');
    fireEvent.mouseDown(combobox);
    // 选项按字典项渲染：itemLabel 展示
    expect(await screen.findByText('启用')).toBeTruthy();
    expect(screen.getByText('停用')).toBeTruthy();
    // 选项来自字典消费面两步序列（裁决⑥）：类型清单 + 字典项各至少一次
    const getsOf = (calls: [Request, RequestInit][], part: string) => calls.filter(
      ([input, init]) => {
        const url = typeof input === 'string' ? new URL(input, 'http://localhost') : new URL((input as Request).url);
        return (init?.method ?? 'GET').toUpperCase() === 'GET' && url.pathname.includes(part);
      },
    );
    expect(getsOf(callsOfResolver(typesHandler), '/dict-types').length).toBeGreaterThanOrEqual(1);
    expect(getsOf(callsOfResolver(itemsHandler), '/items').length).toBeGreaterThanOrEqual(1);
  });

  it('should_emit_item_value_and_reflect_controlled_value_when_option_clicked', async () => {
    seedMe(PERM_DICT_LIST);
    setupDictsApi(['dict-select-emit']);
    const calls: string[] = [];
    render(<Harness dictCode="dict-select-emit" calls={calls} />);

    const combobox = await screen.findByRole('combobox');
    fireEvent.mouseDown(combobox);
    fireEvent.click(await screen.findByText('启用'));

    // 提交 itemValue（非 itemLabel）
    await waitFor(() => expect(calls).toContain('ENABLED'));
    // 受控回路：value 回写后选择器回显 itemLabel——antd5 关闭态选中标签渲染在
    // .ant-select-selection-item（input.value 恒空），读标签元素文本 / title 属性（round1 失败例 4）
    await waitFor(() => {
      const item = document.querySelector('.ant-select-selection-item');
      const label = item?.textContent || item?.getAttribute('title') || '';
      expect(label).toContain('启用');
    });
  });

  it('should_render_nothing_when_dict_permission_missing', async () => {
    seedMe([]); // fail-closed：无任何权限（三段式口径下即空权限集）
    setupDictsApi(['dict-select-noperm']);
    render(<Harness dictCode="dict-select-noperm" calls={[]} />);

    await waitFor(() => {
      expect(screen.queryByRole('combobox')).toBeNull();
    });
  });
});
