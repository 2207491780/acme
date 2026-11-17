import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp, Button, DatePicker, Form, Input } from 'antd';
import dayjs from 'dayjs';
import { i18n, useAuthStore, useCrud, useMeStore, type CrudQuery } from '@acme/core';
import { FilterPanel } from '../src/components/FilterPanel';
import { CrudTable } from '../src/components/CrudTable';
import { AuditPage } from '../src/pages/AuditPage';
import { LoginLogsPage } from '../src/pages/LoginLogsPage';
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

const expand = () => fireEvent.click(screen.getByRole('button', { name: '展开进阶筛选' }));
const collapse = () => fireEvent.click(screen.getByRole('button', { name: '收起进阶筛选' }));

describe('REQ20261117125500 FilterPanel', () => {
  it('starts collapsed, exposes accessible controls, and preserves the mounted field value', () => {
    render(<FilterPanel advanced={<Input aria-label="进阶字段" />}><Input aria-label="基础字段" /></FilterPanel>);
    const toggle = screen.getByRole('button', { name: '展开进阶筛选' });
    const advanced = document.getElementById(toggle.getAttribute('aria-controls')!)!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(advanced.hidden).toBe(true);
    expect(screen.queryByRole('textbox', { name: '进阶字段' })).toBeNull();
    expect(screen.getByText('基础筛选')).toBeTruthy();
    expand();
    expect(advanced.hidden).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('进阶筛选')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: '基础字段' })).toBeTruthy();
    const field = screen.getByRole('textbox', { name: '进阶字段' }) as HTMLInputElement;
    fireEvent.change(field, { target: { value: 'keep-me' } });
    collapse();
    expect(advanced.hidden).toBe(true);
    expand();
    expect(screen.getByRole('textbox', { name: '进阶字段' })).toBe(field);
    expect(field.value).toBe('keep-me');
  });

  it('omits the toggle without advanced fields and translates active-filter feedback', async () => {
    const view = render(<FilterPanel><Input /></FilterPanel>);
    expect(screen.queryByRole('button')).toBeNull();
    view.rerender(<FilterPanel advanced={<Input />} advancedActive><Input /></FilterPanel>);
    expect(screen.getByText('进阶筛选已生效')).toBeTruthy();
    await act(() => i18n.changeLanguage('en-US'));
    expect(screen.getByRole('region', { name: 'Filter criteria' })).toBeTruthy();
    expect(screen.getByText('Basic filters')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Expand advanced filters' }));
    expect(screen.getByText('Advanced filters')).toBeTruthy();
    expect(screen.getByText('Advanced filters active')).toBeTruthy();
  });

  it('submits hidden form fields and the full time range without losing draft values', async () => {
    const submit = vi.fn();
    const range = [dayjs('2026-11-01T08:00:00Z'), dayjs('2026-11-02T09:00:00Z')];
    render(
      <Form initialValues={{ range }} onFinish={submit}>
        <FilterPanel
          advanced={<><Form.Item name="trace"><Input aria-label="Trace" /></Form.Item><Form.Item name="range"><DatePicker.RangePicker showTime /></Form.Item></>}
          actions={<Button htmlType="submit">查询</Button>}
        >
          <Form.Item name="keyword"><Input aria-label="关键词" /></Form.Item>
        </FilterPanel>
      </Form>,
    );
    expand();
    fireEvent.change(screen.getByLabelText('Trace'), { target: { value: 'trace-123' } });
    collapse();
    fireEvent.change(screen.getByLabelText('关键词'), { target: { value: 'alice' } });
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    const values = submit.mock.calls[0]![0];
    expect(values.trace).toBe('trace-123');
    expect(values.keyword).toBe('alice');
    expect(values.range.map((value: dayjs.Dayjs) => value.toISOString())).toEqual(range.map((value) => value.toISOString()));
  });
});

describe('REQ20261117125500 CrudTable separation', () => {
  const fetchPage = vi.fn(async (_query: CrudQuery) => ({ records: [{ id: '1', name: 'first' }], total: 21 }));
  const create = vi.fn();
  function Harness({ searchable = true, permitted = true }: { searchable?: boolean; permitted?: boolean }) {
    const crud = useCrud({ fetchPage, permissions: permitted ? ['item:create'] : [], pageSize: 10 });
    return <CrudTable crud={crud} title="列表" searchable={searchable} columns={[{ title: 'Name', dataIndex: 'name' }]} createPermission="item:create" onCreate={create} actions={<Button>导入</Button>} />;
  }

  it('keeps search above the table, toolbar actions outside filters, and search resets pagination', async () => {
    fetchPage.mockClear();
    create.mockClear();
    render(<Harness />);
    await screen.findByText('first');
    const panel = screen.getByRole('region', { name: '筛选条件' });
    const tableCard = screen.getByRole('table').closest('.ant-card')!;
    expect(panel.contains(tableCard)).toBe(false);
    expect(panel.compareDocumentPosition(tableCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(panel).queryByRole('button', { name: /新\s*建/ })).toBeNull();
    expect(tableCard.contains(screen.getByRole('button', { name: /导\s*入/ }))).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /新\s*建/ }));
    expect(create).toHaveBeenCalledTimes(1);
    fireEvent.click(document.querySelector('.ant-pagination-item-2')!);
    await waitFor(() => expect(fetchPage.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 }));
    const input = screen.getByLabelText('关键词');
    fireEvent.change(input, { target: { value: '  alice  ' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(fetchPage.mock.calls.at(-1)?.[0]).toMatchObject({ page: 1, keyword: 'alice' }));
    const count = fetchPage.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '刷新列表' }));
    await waitFor(() => expect(fetchPage.mock.calls.length).toBeGreaterThan(count));
  });

  it('does not create empty filters or expose unauthorized create actions', async () => {
    render(<Harness searchable={false} permitted={false} />);
    await screen.findByText('first');
    expect(screen.queryByRole('region', { name: '筛选条件' })).toBeNull();
    expect(screen.queryByRole('button', { name: /新\s*建/ })).toBeNull();
    expect(screen.getByRole('button', { name: '刷新列表' })).toBeTruthy();
  });
});

for (const spec of [
  { Component: AuditPage, basic: '操作者 ID', basicKey: 'operatorId', basicValue: '42', advanced: 'Trace ID', advancedKey: 'traceId', advancedValue: 'trace-123', path: '/infra/audit-events' },
  { Component: LoginLogsPage, basic: '用户名', basicKey: 'username', basicValue: 'alice', advanced: '端（userType）', advancedKey: 'userType', advancedValue: 'B', path: '/auth/login-logs' },
]) {
  it(`${spec.Component.name} submits collapsed criteria and clears both groups on reset`, async () => {
    const requests: URL[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      requests.push(new URL(input instanceof Request ? input.url : String(input), 'http://localhost'));
      return new Response(JSON.stringify({ success: true, code: '0', message: null, data: { records: [], total: 0 } }));
    }));
    render(<AntApp><spec.Component /></AntApp>);
    const latest = () => requests.filter((url) => url.pathname.endsWith(spec.path)).at(-1)?.searchParams;
    await waitFor(() => expect(latest()?.get('page')).toBe('1'));
    const panel = screen.getByRole('region', { name: '筛选条件' });
    expect(panel.querySelector('table')).toBeNull();
    expect(panel.closest('.crud-table-card')).toBeNull();
    const count = requests.length;
    expand();
    fireEvent.change(screen.getByLabelText(spec.advanced), { target: { value: spec.advancedValue } });
    collapse();
    expect(requests).toHaveLength(count);
    expect(screen.queryByText('进阶筛选已生效')).toBeNull();
    fireEvent.change(screen.getByLabelText(spec.basic), { target: { value: spec.basicValue } });
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() => expect(latest()?.get(spec.advancedKey)).toBe(spec.advancedValue));
    expect(latest()?.get(spec.basicKey)).toBe(spec.basicValue);
    expect(screen.getByText('进阶筛选已生效')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /重\s*置/ }));
    await waitFor(() => expect(latest()?.has(spec.advancedKey)).toBe(false));
    expect(latest()?.has(spec.basicKey)).toBe(false);
    expect(screen.queryByText('进阶筛选已生效')).toBeNull();
    expand();
    expect((screen.getByLabelText(spec.advanced) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(spec.basic) as HTMLInputElement).value).toBe('');
  });
}

it('Releases retains environment and selection when collapsed and keeps sorting and actions in the table card', async () => {
  render(<AntApp><Releases /></AntApp>);
  const panel = screen.getByRole('region', { name: '筛选条件' });
  const card = document.querySelector('.crud-table-card')!;
  expect(panel.contains(screen.getByLabelText('排序'))).toBe(false);
  expect(card.contains(screen.getByLabelText('排序'))).toBe(true);
  expect(card.contains(screen.getByRole('button', { name: /新建发布单$/ }))).toBe(true);
  expect(card.contains(screen.getByRole('button', { name: /保存视图$/ }))).toBe(true);
  expand();
  fireEvent.mouseDown(within(panel).getByLabelText('环境'));
  const dropdown = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
  fireEvent.click(await within(dropdown).findByText('生产'));
  expect(useStore.getState().query.env).toBe('prod');
  const release = useStore.getState().visibleReleases()[0]!;
  act(() => useStore.getState().setSelection([release.id]));
  collapse();
  expect(useStore.getState().query.env).toBe('prod');
  expect(useStore.getState().selection).toEqual([release.id]);
  expect(screen.getByText('进阶筛选已生效')).toBeTruthy();
  expect(screen.getByRole('button', { name: /批量推进$/ })).toBeTruthy();
  expand();
  expect(within(panel).getByText('生产')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: /保存视图$/ }));
  expect(useStore.getState().modal).toEqual({ type: 'savedView' });
});
