/**
 * REQ-20261114-120101 AC-3：DictSelect 字典选择器——受控 value/onChange、按字典项渲染选项、无权限不渲染。
 *
 * 装配照既有范本 web/tests/REQ20261114071607-usersPage.test.tsx：
 * fetch 边界 stubGlobal mock + 权限经 useMeStore 播种 + <AntApp> 包裹 + testTimeout ≥25000ms。
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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { App as AntApp } from 'antd';
import { DictSelect } from '../src/components/DictSelect';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

const PERM_DICT_LIST = ['system:dict:list'];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

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

const seedMe = async (permissions: string[]) => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: {
      id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
      status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
    },
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
};

/** 字典消费面两步 mock（裁决⑥）：/dict-types 按 dictCode 出类型（id=8801），/{typeId}/items 出字典项。 */
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
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if ((init?.method ?? 'GET').toUpperCase() !== 'GET' || !url.pathname.includes('/dict-types')) {
      return envelope(null);
    }
    if (url.pathname.includes('/items')) {
      return envelope({ records: ITEMS, total: ITEMS.length });
    }
    return envelope({ records: types, total: types.length });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
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
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261114120101 DictSelect（AC-3）', () => {
  it('should_render_options_from_dict_items_when_permitted', async () => {
    await seedMe(PERM_DICT_LIST);
    const { fetchMock } = setupDictsApi(['dict-select-render']);
    render(<Harness dictCode="dict-select-render" calls={[]} />);

    const combobox = await screen.findByRole('combobox');
    fireEvent.mouseDown(combobox);
    // 选项按字典项渲染：itemLabel 展示
    expect(await screen.findByText('启用')).toBeTruthy();
    expect(screen.getByText('停用')).toBeTruthy();
    // 选项来自字典消费面两步序列（裁决⑥）：类型清单 + 字典项各至少一次
    const getsOf = (part: string) => (fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit?][]).filter(
      ([input, init]) => {
        const url = typeof input === 'string' ? new URL(input, 'http://localhost') : new URL((input as Request).url);
        return (init?.method ?? 'GET').toUpperCase() === 'GET' && url.pathname.includes(part);
      },
    );
    expect(getsOf('/dict-types').length).toBeGreaterThanOrEqual(1);
    expect(getsOf('/items').length).toBeGreaterThanOrEqual(1);
  });

  it('should_emit_item_value_and_reflect_controlled_value_when_option_clicked', async () => {
    await seedMe(PERM_DICT_LIST);
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
    await seedMe([]); // fail-closed：无任何权限（三段式口径下即空权限集）
    setupDictsApi(['dict-select-noperm']);
    render(<Harness dictCode="dict-select-noperm" calls={[]} />);

    await waitFor(() => {
      expect(screen.queryByRole('combobox')).toBeNull();
    });
  });
});
