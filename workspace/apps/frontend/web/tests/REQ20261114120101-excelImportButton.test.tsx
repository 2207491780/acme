/**
 * REQ-20261114-120101 AC-6（组件侧）：ExcelImportButton——上传文件至导入端点并展示 ImportReport
 * （errors: row/field/message）；文件无法解析（EXCEL_PARSE_FAILED）→ 统一转译反馈。
 *
 * 端点 = REQ-20261114-071607 §API demo-import 范式（组件对接真实业务端点时按范式传 URL）：
 *   POST multipart（参数名 file，裁决⑦）→ Result<ImportReport>（total/valid/errors[{row,field,message}]）；
 *   业务失败 = Result.code≠0（HTTP 200）。
 *
 * 装配照既有范本 web/tests/REQ20261114071607-usersPage.test.tsx（stubGlobal fetch + <AntApp> +
 * testTimeout ≥15000ms；关闭态断言数据驱动）。
 *
 * 【表面假定（待 TL 裁决）】
 *   A-17 组件路径 web/src/components/ExcelImportButton.tsx，命名导出；props 最小面 { url: string }
 *   A-18 报告展示形态不假设（Modal/Drawer/内联均可）：以 fixtures 中 errors[].field/message 的
 *       文本可见性断言；row 的展示格式（数字/「第 N 行」）不假设 → row 维度不直接断言（071607 ⑨H 先例）
 *   A-19 EXCEL_PARSE_FAILED 转译文案未知 → 宽松正则 /解析|无法|EXCEL_PARSE_FAILED/
 *   A-20 与 antd Upload 类组件一致：隐藏 input[type=file] 注入 File 即触发上传（绕开原生文件对话框）
 *   A-37 组件自身不做权限门禁（门禁由宿主页面负责）；为兼容「组件内查权限」的实现形态，
 *       装配时播种 me 画像（infra:excel:demo，三段式·裁决⑤），播种多余权限不影响断言
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { ExcelImportButton } from '../src/components/ExcelImportButton';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

const DEMO_IMPORT_URL = '/admin/v1/infra/excel/demo-import';
const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const MSG_PARSE_FAILED = /解析|无法|EXCEL_PARSE_FAILED/; // A-19

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

const postsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes('/excel/demo-import') && r.method === 'POST');

const setupImportApi = (opts: { parseFail?: boolean } = {}) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.method === 'GET' && r.url.pathname.includes('/system/me')) {
      return envelope({
        user: {
          id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
          status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
        },
        roles: ['super_admin'],
        permissions: ['infra:excel:demo'],
        menus: [],
      });
    }
    if (r.method === 'POST' && r.url.pathname.includes('/excel/demo-import')) {
      if (opts.parseFail) return failureEnvelope('EXCEL_PARSE_FAILED', 'EXCEL_PARSE_FAILED');
      return envelope({
        total: 3,
        valid: 2,
        errors: [{ row: 3, field: '数量', message: '数量必须为非负数值' }],
      });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

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

const renderButton = async () => {
  // 预热 me 画像（组件若内查权限点则已持有；若不查则无影响，A-37）
  await useMeStore.getState().load();
  render(<AntApp><ExcelImportButton url={DEMO_IMPORT_URL} /></AntApp>);
};

const chooseFile = () => {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  expect(input).toBeTruthy();
  const file = new File([new Uint8Array([1, 2, 3])], 'rows.xlsx', { type: XLSX_TYPE });
  fireEvent.change(input, { target: { files: [file] } });
  return file;
};

describe('REQ20261114120101 ExcelImportButton（AC-6）', () => {
  it('should_post_selected_file_to_import_endpoint_and_show_import_report', async () => {
    const { fetchMock } = setupImportApi();
    await renderButton();
    chooseFile();

    // 上传走导入端点：multipart 参数名 file（裁决⑦）
    await waitFor(() => {
      const posts = postsOf(fetchMock);
      expect(posts).toHaveLength(1);
      const body = posts[0]?.body;
      expect(body).toBeInstanceOf(FormData);
      expect((body as FormData).get('file')).toBeTruthy();
    });

    // ImportReport 展示：errors[].field / message 文本可见（row 展示格式不假设，A-18）
    expect(await screen.findByText('数量必须为非负数值')).toBeTruthy();
    expect(screen.getByText('数量')).toBeTruthy();
  });

  it('should_show_translated_feedback_when_excel_unparseable', async () => {
    const { fetchMock } = setupImportApi({ parseFail: true });
    await renderButton();
    chooseFile();

    await waitFor(() => expect(postsOf(fetchMock)).toHaveLength(1));
    // 既有错误码 EXCEL_PARSE_FAILED 统一转译提示（文案未知 → 宽松正则，A-19）
    expect(await screen.findByText(MSG_PARSE_FAILED)).toBeTruthy();
  });
});
