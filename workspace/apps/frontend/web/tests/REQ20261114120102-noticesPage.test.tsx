/**
 * REQ-20261114-120102 AC-12（公告管理页）：CRUD 对齐 CrudTable 范本语义 + 富文本正文保存/回显一致
 * + 发布动作 + 已发布删除拦截（NOTICE_PUBLISHED）反馈 + 权限显隐。
 *
 * 装配照既有范本 web/tests/REQ20261114120101-crudPages.test.tsx（fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式播种 + <AntApp> 包裹 + testTimeout ≥15000ms + 关闭态断言数据驱动）。
 *
 * 【富文本口径（委派单：tiptap 交互可 stub 编辑器核心，断言 HTML 存取）】
 *   A-14 页面以 web/src/components/RichTextEditor（命名导出 RichTextEditor）承载富文本正文，
 *        受控 props { value?, onChange?(html) }（对齐 DictSelect 受控惯例）。测试以 vi.mock 工厂
 *        将编辑器核心替换为 textarea 桩——页面与编辑器的 HTML 存取边界可精确断言（保存=提交体
 *        含 HTML；回显=编辑器承载既有 HTML），tiptap 编辑交互本身不做断言。
 *        风险注记：vitest 对「尚未落地模块」的工厂 mock 依赖其虚拟 mock 能力；若运行时解析失败，
 *        提请 TL 裁决改为实现侧注入点或其他 stub 形态。
 *
 * 【表面假定（编号全测试套件连续，汇总见交付说明；待 TL 裁决）】
 *   A-20 页面 web/src/pages/NoticesPage.tsx 命名导出 NoticesPage，自取数据零必填 props；
 *        弹窗字段 label「标题」（getByLabelText）与「正文」（经编辑器桩 aria-label「公告正文」）；
 *        两字按钮 autoInsertSpace 兼容正则（crudPages 口径）。
 *   A-15 AdminNoticeView wire 形状假定：id/title/content/status/publishAt/createdAt/version，
 *        status wire 名 DRAFT|PUBLISHED|OFFLINE（OFFLINE 为 TL 裁决⑫新增），camelCase、id 字符串、
 *        时间 ISO-8601（对齐 Admin*View 惯例）。
 *   A-17 已发布删除被拦（NOTICE_PUBLISHED，契约 §API）反馈文案未知 → 宽松正则
 *        /已发布|下线|NOTICE_PUBLISHED/（filesPage A-29 口径）；断言收敛于 .ant-message 容器，
 *        避免与 PUBLISHED 行内「下线」按钮文本撞车。
 *   A-18 发布/下线动作（TL 裁决⑫钉死）：行内「发布」仅草稿行，直接点击即 PUT {id}/publish；
 *        PUBLISHED 行显示行内「下线」，点击即 PUT {id}/offline（PUBLISHED→OFFLINE，权限复用
 *        system:notice:publish；OFFLINE 后可编辑/删除/再发布）；重复发布幂等/全员扇出为后端
 *        AC-2 语义，前端不测。
 *   A-7 公告页权限点（三段式）：system:notice:list/create/update/delete/publish。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { NoticesPage } from '../src/pages/NoticesPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// 编辑器核心 stub（A-14）：页面 ↔ 编辑器的 value/onChange HTML 边界保持真实，tiptap 交互替换为 textarea
vi.mock('../src/components/RichTextEditor', async () => {
  const { createElement } = await import('react');
  type EditorProps = { value?: string; onChange?: (html: string) => void };
  const RichTextEditor = (props: EditorProps) => createElement('textarea', {
    'aria-label': '公告正文',
    value: props.value ?? '',
    onChange: (event: { target: { value: string } }) => props.onChange?.(event.target.value),
  });
  return { RichTextEditor };
});

// ————— 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;
const PUBLISH_BUTTON = /发\s*布/;
const OFFLINE_BUTTON = /下\s*线/; // 裁决⑫：PUBLISHED 行内下线动作
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const MSG_PUBLISHED_BLOCKED = /已发布|下线|NOTICE_PUBLISHED/; // A-17

const PERM = {
  list: ['system:notice:list'],
  create: ['system:notice:create'],
  update: ['system:notice:update'],
  delete: ['system:notice:delete'],
  publish: ['system:notice:publish'],
};

const NOTICES_PATH_PART = '/notices';

/**
 * mock 行宽松形状（round0 复核·修复 3）：AdminNoticeView 形状（A-15）、除 id 外字段可选并放宽为
 * string——POST/PUT 把 body（Record<string, unknown>）merge 进行对象后，产物仍可直接赋回行数组，
 * 不再与推导出的严格字面量行类型冲突（纯用例侧类型面，实现不可解）。
 */
type NoticeRow = {
  id: string;
  title?: string;
  content?: string;
  status?: string;
  publishAt?: string | null;
  createdAt?: string;
  version?: string;
};

// fixtures（A-15 wire 形状；标题避开 ADD/PUBLISH 正则撞词）
const NOTICE_ROWS: NoticeRow[] = [
  { id: '6001', title: '停机维护公告', content: '<p>维护内容甲</p>', status: 'PUBLISHED', publishAt: '2026-11-01T09:00:00Z', createdAt: '2026-11-01T08:00:00Z', version: '0' },
  { id: '6002', title: '新功能上线预告', content: '<p>预告内容乙</p>', status: 'DRAFT', publishAt: null, createdAt: '2026-11-02T08:00:00Z', version: '0' },
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(NOTICES_PATH_PART) && r.method === method);

const updateCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  callsOf(fetchMock, 'PUT').filter((r) => !r.url.pathname.endsWith('/publish'));

const setupNoticesApi = () => {
  const state = { rows: NOTICE_ROWS.map((row) => ({ ...row })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (!p.includes(NOTICES_PATH_PART)) return envelope(null);
    if (r.method === 'GET') {
      // 详情端点 GET {id}（编辑回显可经详情或行数据承载，mock 双兼容）
      const detail = p.match(/\/notices\/(\d+)$/);
      if (detail) return envelope(state.rows.find((row) => row.id === detail[1]) ?? null);
      return envelope({ records: state.rows, total: state.rows.length });
    }
    if (r.method === 'POST') {
      const body = parseBody(r.body);
      // publishAt 显式放宽（null 字面量在宽松行类型下仍按 string | null 承载）
      const created = { id: '9901', status: 'DRAFT', publishAt: null as string | null, ...body };
      state.rows = [...state.rows, created];
      return envelope(created);
    }
    if (r.method === 'PUT') {
      // 发布/下线子资源（裁决⑫）：/publish（→PUBLISHED）与 /offline（→OFFLINE）先行识别，避免 id 误取子资源名
      if (p.endsWith('/publish') || p.endsWith('/offline')) {
        const segments = p.split('/').filter(Boolean);
        const id = segments[segments.length - 2] ?? '';
        const nextStatus = p.endsWith('/publish') ? 'PUBLISHED' : 'OFFLINE';
        state.rows = state.rows.map((row) => (row.id === id
          ? { ...row, status: nextStatus, ...(nextStatus === 'PUBLISHED' ? { publishAt: '2026-11-14T09:00:00Z' } : {}) }
          : row));
        return envelope(state.rows.find((row) => row.id === id) ?? null);
      }
      const id = p.split('/').filter(Boolean).pop() ?? '';
      const body = parseBody(r.body);
      state.rows = state.rows.map((row) => (row.id === id ? { ...row, ...body } : row));
      return envelope(state.rows.find((row) => row.id === id) ?? null);
    }
    if (r.method === 'DELETE') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      const row = state.rows.find((item) => item.id === id);
      // 已发布需先下线（契约 §API 错误码 NOTICE_PUBLISHED）
      if (row?.status === 'PUBLISHED') return failureEnvelope('NOTICE_PUBLISHED', '已发布公告需先下线');
      state.rows = state.rows.filter((item) => item.id !== id);
      return envelope(null); // 软删幂等成功
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

// ————— 装配 —————
const stubBrowserGaps = () => {
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

const renderPage = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupNoticesApi();
  render(<AntApp><NoticesPage /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
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

describe('REQ20261114120102 公告管理页（AC-12）', () => {
  it('should_render_notice_list_from_existing_endpoint_when_page_loads', async () => {
    const { fetchMock } = await renderPage(PERM.list);

    expect(await screen.findByText('停机维护公告')).toBeTruthy();
    expect(screen.getByText('新功能上线预告')).toBeTruthy();
    // 列表请求携带分页参数（CrudTable page=1 口径）
    const query = callsOf(fetchMock, 'GET').at(-1)?.url.searchParams;
    expect(query?.get('page')).toBe('1');
  });

  it('should_submit_create_with_rich_text_html_when_add_confirmed', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.create]);
    await screen.findByText('停机维护公告');

    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('标题'), { target: { value: '2026年度安全演练安排' } });
    // 编辑器桩（A-14）：HTML 字符串原样经 onChange 提交
    fireEvent.change(within(dialog).getByLabelText('公告正文'), { target: { value: '<p>演练正文<strong>加粗片段</strong></p>' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    // 保存：POST 体承载标题 + 富文本 HTML（存取一致之「存」）
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST');
      expect(posts).toHaveLength(1);
      const bodyJson = JSON.stringify(parseBody(posts[0]?.body));
      expect(bodyJson).toContain('2026年度安全演练安排');
      expect(bodyJson).toContain('<p>演练正文<strong>加粗片段</strong></p>');
    });
    // 关闭态数据驱动（071607 裁决⑪）：提交成功后的 reload 二次 GET
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_prefill_editor_html_and_submit_update_when_row_edit_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.update]);
    const rowEl = await findRowByText('新功能上线预告');

    fireEvent.click(await within(rowEl).findByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');

    // 回显（AC-12 核心）：编辑器承载既有富文本 HTML（存取一致之「取」）
    await waitFor(() => {
      const editor = within(dialog).getByLabelText('公告正文') as HTMLTextAreaElement;
      expect(editor.value).toBe('<p>预告内容乙</p>');
    });
    fireEvent.change(within(dialog).getByLabelText('公告正文'), { target: { value: '<p>改后富文本正文</p>' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const updates = updateCallsOf(fetchMock);
      expect(updates).toHaveLength(1);
      expect(JSON.stringify(parseBody(updates[0]?.body))).toContain('<p>改后富文本正文</p>');
    });
  });

  it('should_publish_draft_via_publish_endpoint_when_row_publish_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.publish]);
    const rowEl = await findRowByText('新功能上线预告'); // 草稿行（A-18）

    fireEvent.click(await within(rowEl).findByText(PUBLISH_BUTTON));

    await waitFor(() => {
      const publishes = callsOf(fetchMock, 'PUT').filter((r) => r.url.pathname.endsWith('/publish'));
      expect(publishes).toHaveLength(1);
      expect(publishes[0]?.url.pathname).toContain('/6002/');
    });
    // 发布后 reload（数据驱动）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_request_offline_endpoint_when_offline_action_clicked_on_published_row', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.publish]); // 下线复用 publish 权限点（裁决⑫）
    const rowEl = await findRowByText('停机维护公告'); // PUBLISHED 行显示行内「下线」（A-18）

    fireEvent.click(await within(rowEl).findByText(OFFLINE_BUTTON));

    await waitFor(() => {
      const offlines = callsOf(fetchMock, 'PUT').filter((r) => r.url.pathname.endsWith('/offline'));
      expect(offlines).toHaveLength(1);
      expect(offlines[0]?.url.pathname).toContain('/6001/');
    });
  });

  it('should_delete_draft_row_after_confirm_when_row_delete_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('新功能上线预告'); // 草稿行可直接删

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.pathname).toContain('6002');
    });
    await waitFor(() => expect(screen.queryByText('新功能上线预告')).toBeNull());
    expect(screen.getByText('停机维护公告')).toBeTruthy();
  });

  it('should_show_translated_feedback_and_keep_row_when_published_delete_rejected', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('停机维护公告'); // 已发布行（NOTICE_PUBLISHED，A-17）

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(callsOf(fetchMock, 'DELETE')).toHaveLength(1));
    // 统一转译反馈（文案未知 → 宽松正则，A-17）：收敛于 antd message 容器，避开行内「下线」按钮文本撞车
    await waitFor(() => {
      expect(document.querySelector('.ant-message')?.textContent ?? '').toMatch(MSG_PUBLISHED_BLOCKED);
    });
    // 行保留（数据驱动：未删除）
    expect(screen.getByText('停机维护公告')).toBeTruthy();
  });

  it('should_hide_unauthorized_actions_when_notice_permissions_missing', async () => {
    await renderPage(PERM.list); // 仅 list 权限（A-7）
    await screen.findByText('停机维护公告');

    expect(screen.queryByText(ADD_BUTTON)).toBeNull();
    const rowEl = await findRowByText('新功能上线预告');
    expect(within(rowEl).queryByText(EDIT_BUTTON)).toBeNull();
    expect(within(rowEl).queryByText(DELETE_BUTTON)).toBeNull();
    expect(within(rowEl).queryByText(PUBLISH_BUTTON)).toBeNull();
  });
});
