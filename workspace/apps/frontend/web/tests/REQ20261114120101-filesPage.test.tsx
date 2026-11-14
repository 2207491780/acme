/**
 * REQ-20261114-120101 AC-4 / AC-5：FilesPage 文件管理页——上传（含错误码转译）/ 列表 / 下载 / 删除。
 *
 * 装配照既有范本 web/tests/REQ20261114071607-usersPage.test.tsx：
 * fetch 边界 stubGlobal mock（弱匹配 URL 路由）+ 权限经 useMeStore 播种 + <AntApp> 包裹 + testTimeout ≥15000ms；
 * 关闭态断言一律数据驱动（jsdom25+cssstyle4.6 下 antd 关闭动效永不结束，071607 裁决⑪）。
 *
 * 端点/DTO 口径 = REQ-20261114-071607 §API：
 *   POST/GET /admin/v1/infra/files、GET /admin/v1/infra/files/{id}/content、DELETE /admin/v1/infra/files/{id}；
 *   FileView = id/originalName/size/contentType/url/bizType/bizId/createdAt（id 字符串形态）；
 *   上传 multipart 参数名 file（裁决⑦）；业务失败 = Result.code≠0（HTTP 200）。
 *
 * 【表面口径（round1 部分裁决钉死）】
 *   A-26 页面路径 web/src/pages/FilesPage.tsx，命名导出 FilesPage；页面自取数据（零必填 props）
 *   A-27 页面权限点 = 契约钉死的 infra:file:*，三段式原样播种（round1 裁决⑤：me.permissions
 *       =后端三段式码，双拼写播种已废除）
 *   A-28 行内动作按钮为文本按钮（下载/删除，兼容 antd 两字按钮 autoInsertSpace 插空正则）；
 *       上传入口按钮文案含「上传」
 *   A-29 错误码转译文案未知 → 以宽松正则断言「出现反馈」：大小类 /大小|超|上限/、类型类 /类型|支持|白名单/
 *   A-30 上传进度反馈为视觉行为，维持不测（071607 裁决⑨H 先例：未断言项维持不测）
 *   A-31 下载「原名」的文件名接线（Content-Disposition 解析→download 属性）在 UI 黑盒下
 *       不可精确断言，断言退至「content 端点被调用 + blob 对象 URL 建立」；精确文件名建议补 util 单测
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { FilesPage } from '../src/pages/FilesPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// ————— 表面常量 —————
const FILES_PATH_PART = '/files';                 // files 接口 URL 公共子串（弱匹配路由用）
const DOWNLOAD_BUTTON = /下\s*载/;                // 行内下载动作
const DELETE_BUTTON = /删\s*除/;                  // 行内删除动作
const UPLOAD_BUTTON_TEXT = /上\s*传/;             // 上传入口（文案假定，A-28）
const CONFIRM_BUTTON = /确\s*定|^OK$/i;           // 兼容 zh_CN「确 定/确定」与 en「OK」
const MSG_SIZE_EXCEEDED = /大小|超|上限/;          // FILE_SIZE_EXCEEDED 转译反馈（宽松，A-29）
const MSG_TYPE_REJECTED = /类型|支持|白名单/;      // FILE_TYPE_REJECTED 转译反馈（宽松，A-29）

const PERM = {
  list: ['infra:file:list'],
  upload: ['infra:file:upload'],
  download: ['infra:file:download'],
  delete: ['infra:file:delete'],
};
const ALL_PERMS = [...PERM.list, ...PERM.upload, ...PERM.download, ...PERM.delete];
const LIST_ONLY = PERM.list;

// ————— fixtures（FileView 口径：REQ-071607 §API / InfraApiContractTest fileViewShapeLocked） —————
type FileRow = {
  id: string; originalName: string; size: number; contentType: string;
  url: string; bizType: string; bizId: string; createdAt: string;
};

const fileRow = (id: string, originalName: string, contentType: string): FileRow => ({
  id, originalName, size: 1024, contentType,
  url: `http://files.localhost/acme/files/2026/11/14/${id}`,
  bizType: 'demo', bizId: '42', createdAt: '2026-11-14T08:30:00Z',
});

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

// ————— fetch 边界 mock —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, extraPart = '') =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(FILES_PATH_PART) && r.method === method
      && (extraPart === '' || r.url.pathname.includes(extraPart)));

const setupFilesApi = (opts: { uploadFailCode?: string } = {}) => {
  const state = {
    rows: [
      fileRow('9001', '季度报表.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
      fileRow('9002', '合同扫描件.pdf', 'application/pdf'),
    ] as FileRow[],
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p.includes('/files/') && p.endsWith('/content') && r.method === 'GET') {
      // Content-Disposition 原名口径：ASCII 直用 + 非 ASCII RFC 5987 filename*（071607 裁决⑦）
      return new Response(new Blob(['file-bytes'], { type: 'application/octet-stream' }), {
        status: 200,
        headers: {
          'Content-Disposition': `attachment; filename="export.xlsx"; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%E6%8A%A5%E8%A1%A8.xlsx`,
        },
      });
    }
    if (r.method === 'GET' && p.includes(FILES_PATH_PART)) {
      return envelope({ records: state.rows, total: state.rows.length });
    }
    if (r.method === 'POST' && p.includes(FILES_PATH_PART)) {
      if (opts.uploadFailCode) return failureEnvelope(opts.uploadFailCode, opts.uploadFailCode);
      const created = fileRow('9003', '上传成功.txt', 'text/plain');
      state.rows = [...state.rows, created];
      return envelope(created);
    }
    if (r.method === 'DELETE' && p.includes(`${FILES_PATH_PART}/`)) {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      state.rows = state.rows.filter((item) => item.id !== id);
      return envelope(null); // 软删幂等：已删/对象不存在均成功（071607 §API）
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
  // jsdom 缺省无 createObjectURL：下载链路打桩（A-31），afterEach 手工卸桩
  (URL as unknown as { createObjectURL?: unknown }).createObjectURL = vi.fn(() => 'blob:acme-mock');
  (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL = vi.fn();
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

const renderPage = async (permissions: string[], opts: { uploadFailCode?: string } = {}) => {
  await seedMe(permissions);
  const api = setupFilesApi(opts);
  render(<AntApp><FilesPage /></AntApp>);
  return api;
};

const findRowByName = async (originalName: string) => {
  const cell = await screen.findByText(originalName);
  return cell.closest('tr') as HTMLElement;
};

const pickFile = (name: string, type: string) =>
  new File([new Uint8Array([1, 2, 3])], name, { type });

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
  delete (URL as unknown as { createObjectURL?: unknown }).createObjectURL;
  delete (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL;
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261114120101 FilesPage（AC-4 / AC-5）', () => {
  it('should_render_file_list_from_existing_endpoint_when_page_loads', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS);
    expect(await screen.findByText('季度报表.xlsx')).toBeTruthy();
    expect(screen.getByText('合同扫描件.pdf')).toBeTruthy();
    // 分页参数随列表请求上送（page/size 口径见 071607 §API）
    const gets = callsOf(fetchMock, 'GET');
    expect(gets.length).toBeGreaterThanOrEqual(1);
    const query = gets.at(-1)?.url.searchParams;
    expect(query?.get('page')).toBe('1');
  });

  it('should_refresh_list_after_successful_upload_when_valid_file_selected', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS);
    await screen.findByText('季度报表.xlsx');

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    const file = pickFile('上传.txt', 'text/plain');
    fireEvent.change(input, { target: { files: [file] } });

    // 上传走既有端点：POST /admin/v1/infra/files，multipart 参数名 file（裁决⑦）
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST');
      expect(posts).toHaveLength(1);
      const body = posts[0]?.body;
      expect(body).toBeInstanceOf(FormData);
      expect((body as FormData).get('file')).toBeTruthy();
    });
    // 成功刷新列表：数据驱动断言（等 reload 的二次 GET）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
    });
    expect(await screen.findByText('上传成功.txt')).toBeTruthy();
  });

  it('should_show_translated_feedback_when_upload_size_exceeded', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS, { uploadFailCode: 'FILE_SIZE_EXCEEDED' });
    await screen.findByText('季度报表.xlsx');

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [pickFile('big.txt', 'text/plain')] } });

    await waitFor(() => expect(callsOf(fetchMock, 'POST')).toHaveLength(1));
    // 既有错误码 FILE_SIZE_EXCEEDED 统一转译提示（文案未知 → 宽松正则，A-29）
    expect(await screen.findByText(MSG_SIZE_EXCEEDED)).toBeTruthy();
  });

  it('should_show_translated_feedback_when_upload_type_rejected', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS, { uploadFailCode: 'FILE_TYPE_REJECTED' });
    await screen.findByText('季度报表.xlsx');

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [pickFile('evil.exe', 'application/x-msdownload')] } });

    await waitFor(() => expect(callsOf(fetchMock, 'POST')).toHaveLength(1));
    // 既有错误码 FILE_TYPE_REJECTED 统一转译提示（文案未知 → 宽松正则，A-29）
    expect(await screen.findByText(MSG_TYPE_REJECTED)).toBeTruthy();
  });

  it('should_fetch_content_endpoint_when_row_download_clicked', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS);
    const rowEl = await findRowByName('季度报表.xlsx');

    fireEvent.click(await within(rowEl).findByText(DOWNLOAD_BUTTON));

    // 下载走 content 端点（流式），blob 对象 URL 建立（文件名接线不黑盒断言，A-31）
    await waitFor(() => {
      const contents = callsOf(fetchMock, 'GET', '/content');
      expect(contents).toHaveLength(1);
      expect(contents[0]?.url.pathname).toContain('/9001/');
    });
  });

  it('should_delete_row_after_confirm_and_reload_when_row_delete_clicked', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS);
    const rowEl = await findRowByName('合同扫描件.pdf');

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    // 软删幂等：DELETE /admin/v1/infra/files/{id}，成功后刷新（数据驱动）
    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.pathname).toContain('9002');
    });
    await waitFor(() => expect(screen.queryByText('合同扫描件.pdf')).toBeNull());
    expect(screen.getByText('季度报表.xlsx')).toBeTruthy();
  });

  it('should_hide_upload_and_row_actions_when_permissions_missing', async () => {
    await renderPage(LIST_ONLY);
    await screen.findByText('季度报表.xlsx');

    // 仅 list 权限：上传入口与下载/删除动作均隐藏（fail-closed 显隐）
    expect(screen.queryByText(UPLOAD_BUTTON_TEXT)).toBeNull();
    const rowEl = await findRowByName('季度报表.xlsx');
    expect(within(rowEl).queryByText(DOWNLOAD_BUTTON)).toBeNull();
    expect(within(rowEl).queryByText(DELETE_BUTTON)).toBeNull();
  });
});
