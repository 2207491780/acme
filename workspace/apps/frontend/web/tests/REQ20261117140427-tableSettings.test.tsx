import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { i18n, useAuthStore, useCrud, useMeStore } from '@acme/core';
import { CrudTable } from '../src/components/CrudTable';
import { Releases } from '../src/pages/Releases';
import { DEFAULT_QUERY, useStore } from '../src/store/useStore';

vi.setConfig({ testTimeout: 25000 });

beforeEach(async () => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useStore.setState({ query: { ...DEFAULT_QUERY }, selection: [], modal: null });
  await i18n.changeLanguage('zh-CN');
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
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  await i18n.changeLanguage('zh-CN');
});

interface Row {
  id: string;
  name: string;
  status: string;
  score: number;
}

const ROWS: Row[] = [
  { id: '1', name: 'alpha', status: 'ENABLED', score: 3 },
  { id: '2', name: 'beta', status: 'DISABLED', score: 1 },
  { id: '3', name: 'gamma', status: 'ENABLED', score: 2 },
  { id: '4', name: 'delta', status: 'DISABLED', score: 3 },
];

const COLUMNS: ColumnsType<Row> = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '分数', dataIndex: 'score', key: 'score' },
  { title: '操作', key: 'actions', render: () => '—' },
];

function Harness({ tableKey, rows = ROWS, pageSize = 10 }: { tableKey?: string; rows?: Row[]; pageSize?: number }) {
  const crud = useCrud<Row>({
    fetchPage: async () => ({ records: rows, total: rows.length }),
    pageSize,
  });
  return <CrudTable<Row> crud={crud} tableKey={tableKey} title="列表" searchable={false} columns={COLUMNS} />;
}

const openSettings = async () => {
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  return screen.findByRole('switch', { name: '显示边框' });
};

const headerTexts = () => [...document.querySelectorAll('.ant-table-thead th')].map((th) => th.textContent ?? '');
const firstCellTexts = () => [...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')].map((tr) => tr.querySelector('td')?.textContent ?? '');
const headerByTitle = (title: string) => [...document.querySelectorAll('.ant-table-thead th')].find((th) => th.textContent?.includes(title))!;

const clickFilterConfirm = (dropdown: Element) => {
  fireEvent.click(dropdown.querySelector('.ant-table-filter-dropdown-btns .ant-btn-primary')!);
};

const visibleFilterDropdown = async () => waitFor(() => {
  const el = document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown');
  expect(el).toBeTruthy();
  return el as HTMLElement;
});

describe('REQ20261117140427 列设置（显隐/固定/边框/持久化）', () => {
  it('should_hide_column_and_guard_last_visible_when_toggling_checkboxes', async () => {
    render(<AntApp><Harness tableKey="t-hide" /></AntApp>);
    await screen.findByText('alpha');
    await openSettings();
    expect(headerTexts().some((text) => text.includes('状态'))).toBe(true);
    fireEvent.click(screen.getByRole('checkbox', { name: '状态' }));
    await waitFor(() => expect(headerTexts().some((text) => text.includes('状态'))).toBe(false));
    expect(screen.queryByRole('cell', { name: 'ENABLED' })).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: '分数' }));
    fireEvent.click(screen.getByRole('checkbox', { name: '操作' }));
    await waitFor(() => expect((screen.getByRole('checkbox', { name: '名称' }) as HTMLInputElement).disabled).toBe(true));
    expect(headerTexts().filter((text) => text.trim().length > 0)).toHaveLength(1);
  });

  it('should_move_pinned_columns_to_edges_when_setting_fixed_left_and_right', async () => {
    render(<AntApp><Harness tableKey="t-pin" /></AntApp>);
    await screen.findByText('alpha');
    await openSettings();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '固定 分数' }));
    const rightDropdown = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
    fireEvent.click(await within(rightDropdown).findByText('固定右侧'));
    await waitFor(() => expect(headerTexts().at(-1)).toContain('分数'));
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '固定 状态' }));
    const leftDropdown = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
    fireEvent.click(await within(leftDropdown).findByText('固定左侧'));
    await waitFor(() => expect(headerTexts()[0]).toContain('状态'));
    expect(headerTexts().at(-1)).toContain('分数');
    expect(document.querySelector('.ant-table-cell-fix-left')).toBeTruthy();
    expect(document.querySelector('.ant-table-cell-fix-right')).toBeTruthy();
  });

  it('should_toggle_bordered_and_reset_defaults_when_using_panel_controls', async () => {
    render(<AntApp><Harness tableKey="t-border" /></AntApp>);
    await screen.findByText('alpha');
    const borderedSwitch = await openSettings();
    expect(document.querySelector('.ant-table-bordered')).toBeNull();
    fireEvent.click(borderedSwitch);
    await waitFor(() => expect(document.querySelector('.ant-table-bordered')).toBeTruthy());
    fireEvent.click(screen.getByRole('checkbox', { name: '状态' }));
    await waitFor(() => expect(headerTexts().some((text) => text.includes('状态'))).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '重置默认' }));
    await waitFor(() => expect(headerTexts().some((text) => text.includes('状态'))).toBe(true));
    expect(document.querySelector('.ant-table-bordered')).toBeNull();
    const stored = JSON.parse(window.localStorage.getItem('acme.table-settings.v1:t-border')!) as { bordered: boolean; columns: Record<string, { visible: boolean }> };
    expect(stored.bordered).toBe(false);
    expect(stored.columns.status!.visible).toBe(true);
  });

  it('should_restore_settings_when_remounting_with_same_table_key', async () => {
    const view = render(<AntApp><Harness tableKey="t-persist" /></AntApp>);
    await screen.findByText('alpha');
    const borderedSwitch = await openSettings();
    fireEvent.click(borderedSwitch);
    fireEvent.click(screen.getByRole('checkbox', { name: '状态' }));
    await waitFor(() => {
      const stored = JSON.parse(window.localStorage.getItem('acme.table-settings.v1:t-persist')!) as { bordered: boolean; columns: Record<string, { visible: boolean }> };
      expect(stored.bordered).toBe(true);
      expect(stored.columns.status!.visible).toBe(false);
    });
    view.unmount();
    render(<AntApp><Harness tableKey="t-persist" /></AntApp>);
    await screen.findByText('alpha');
    expect(headerTexts().some((text) => text.includes('状态'))).toBe(false);
    expect(document.querySelector('.ant-table-bordered')).toBeTruthy();
  });

  it('should_fall_back_to_defaults_when_stored_settings_are_corrupt', async () => {
    window.localStorage.setItem('acme.table-settings.v1:t-corrupt', '{broken json');
    window.localStorage.setItem('acme.table-settings.v1:t-shape', JSON.stringify({ bordered: 'yes', columns: [] }));
    const view = render(<AntApp><Harness tableKey="t-corrupt" /></AntApp>);
    await screen.findByText('alpha');
    expect(headerTexts().some((text) => text.includes('状态'))).toBe(true);
    expect(document.querySelector('.ant-table-bordered')).toBeNull();
    view.unmount();
    render(<AntApp><Harness tableKey="t-shape" /></AntApp>);
    await screen.findByText('alpha');
    expect(headerTexts().some((text) => text.includes('状态'))).toBe(true);
    expect(document.querySelector('.ant-table-bordered')).toBeNull();
  });

  it('should_bypass_enhancement_when_table_key_is_absent', async () => {
    render(<AntApp><Harness /></AntApp>);
    await screen.findByText('alpha');
    expect(screen.queryByRole('button', { name: '列设置' })).toBeNull();
    expect(document.querySelector('.ant-table-filter-trigger')).toBeNull();
    expect(document.querySelector('.ant-table-column-sorter')).toBeNull();
  });
});

describe('REQ20261117140427 多列筛选与排序', () => {
  it('should_combine_enum_filters_across_columns_when_confirmed', async () => {
    render(<AntApp><Harness tableKey="t-filter" /></AntApp>);
    await screen.findByText('alpha');
    fireEvent.click(headerByTitle('状态').querySelector('.ant-table-filter-trigger')!);
    const statusDropdown = await visibleFilterDropdown();
    fireEvent.click(within(statusDropdown).getByText('ENABLED'));
    clickFilterConfirm(statusDropdown);
    await waitFor(() => expect(firstCellTexts()).toEqual(['alpha', 'gamma']));
    fireEvent.click(headerByTitle('分数').querySelector('.ant-table-filter-trigger')!);
    const scoreDropdown = await visibleFilterDropdown();
    fireEvent.click(within(scoreDropdown).getByText('3'));
    clickFilterConfirm(scoreDropdown);
    await waitFor(() => expect(firstCellTexts()).toEqual(['alpha']));
  });

  it('should_filter_by_keyword_when_column_has_high_cardinality', async () => {
    const rows: Row[] = Array.from({ length: 25 }, (_, i) => ({
      id: String(i + 1),
      name: `user-${String(i + 1).padStart(2, '0')}`,
      status: 'ENABLED',
      score: i,
    }));
    render(<AntApp><Harness tableKey="t-search" rows={rows} pageSize={30} /></AntApp>);
    await screen.findByText('user-01');
    fireEvent.click(headerByTitle('名称').querySelector('.ant-table-filter-trigger')!);
    const input = await screen.findByPlaceholderText('输入关键词筛选');
    fireEvent.change(input, { target: { value: 'user-2' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(firstCellTexts()).toEqual(['user-20', 'user-21', 'user-22', 'user-23', 'user-24', 'user-25']));
    expect(document.querySelectorAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(6);
  });

  it('should_apply_multi_column_sort_priority_when_sorting_two_columns', async () => {
    render(<AntApp><Harness tableKey="t-sort" /></AntApp>);
    await screen.findByText('alpha');
    fireEvent.click(headerByTitle('分数'));
    await waitFor(() => expect(firstCellTexts()).toEqual(['beta', 'gamma', 'alpha', 'delta']));
    fireEvent.click(headerByTitle('状态'));
    await waitFor(() => expect(firstCellTexts()).toEqual(['beta', 'delta', 'gamma', 'alpha']));
    expect(document.querySelectorAll('.ant-table-column-sort').length).toBeGreaterThanOrEqual(2);
  });
});

describe('REQ20261117140427 页面接入', () => {
  it('should_expose_settings_with_all_data_scope_when_rendering_releases', async () => {
    render(<AntApp><Releases /></AntApp>);
    const card = document.querySelector('.crud-table-card')!;
    expect(within(card as HTMLElement).getByRole('button', { name: '列设置' })).toBeTruthy();
    const borderedSwitch = await openSettings();
    expect(screen.getByText('列筛选与排序作用于全部已加载数据')).toBeTruthy();
    fireEvent.click(borderedSwitch);
    await waitFor(() => expect(card.querySelector('.ant-table-bordered')).toBeTruthy());
    expect(screen.getByLabelText('排序')).toBeTruthy();
    expect(within(card as HTMLElement).getByRole('button', { name: /新建发布单$/ })).toBeTruthy();
  });
});