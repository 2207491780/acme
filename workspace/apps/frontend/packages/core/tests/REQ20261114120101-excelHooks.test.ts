/**
 * @vitest-environment jsdom
 *
 * REQ-20261114-120101 AC-6（headless 侧）：useExcelExport / useExcelImport 对接任意导出/导入端点。
 *
 * 端点用既有 Excel demo 范式（REQ-20261114-071607 §API）：GET demo-export 触发下载、
 * POST demo-import 上传 multipart 并返回 Result<ImportReport>；文件非法 = EXCEL_PARSE_FAILED。
 *
 * 【表面假定（待 TL 裁决；若不符只需改本文件 import/调用行）】
 *   A-4 模块路径：core 平铺 src/use-excel.ts，命名导出 useExcelExport / useExcelImport
 *   A-5 useExcelExport() → { exporting: boolean; exportFile(url: string): Promise<void> }
 *   A-6 useExcelImport() → { importing: boolean; importFile(url, file): Promise<ImportReport>;
 *       report: ImportReport | null; error: unknown }
 *   A-7 ImportReport 形状 = { total, valid, errors: [{ row, field, message }] }（071607 裁决⑦：
 *       errors.field=字段名、errors.row=数据行自 1 起）
 *   A-8 导出响应为裸字节流（非 Result 信封），Content-Disposition 携带文件名（ASCII 直用 /
 *       非 ASCII RFC 5987 filename*）
 *   A-9 导入 multipart 字段名 = file（071607 裁决⑦）
 *   A-10 导入失败语义（抛出或置 error 状态）用「错误文本含错误码」宽松断言兼容两种
 *
 * 【环境】导出/下载经 blob → URL.createObjectURL（jsdom 缺省无此 API，用例内显式打桩）。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useExcelExport, useExcelImport } from '../src/use-excel';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.setConfig({ testTimeout: 15000 });

const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

/** 极简 renderHook 替身（照抄 use-crud.test.ts）。 */
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
type ParsedCall = { url: URL; method: string; body: BodyInit | null | undefined };

const asRequest = (input: RequestInfo | URL, init?: RequestInit): ParsedCall => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, part: string): ParsedCall[] =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(part) && r.method === method);

/** 错误对象 → 含错误码的文本（兼容 ApiError.code / message / 纯字符串转译三种形态）。 */
const errText = (e: unknown): string => {
  if (e == null) return '';
  if (typeof e === 'string') return e;
  const obj = e as { code?: string; message?: string };
  return `${obj.code ?? ''} ${obj.message ?? ''}`;
};

const report = {
  total: 3,
  valid: 2,
  errors: [{ row: 3, field: '数量', message: '数量必须为非负数值' }],
};

let createObjectURLMock: ReturnType<typeof vi.fn>;
let revokeObjectURLMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  createObjectURLMock = vi.fn(() => 'blob:acme-mock');
  revokeObjectURLMock = vi.fn();
  (URL as unknown as { createObjectURL?: unknown }).createObjectURL = createObjectURLMock;
  (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL = revokeObjectURLMock;
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (URL as unknown as { createObjectURL?: unknown }).createObjectURL;
  delete (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL;
});

const setupExcelApi = (importResponse?: () => Response) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.method === 'GET' && r.url.pathname.includes('/excel/demo-export')) {
      return new Response(new Blob(['excel-bytes'], { type: XLSX_TYPE }), {
        status: 200,
        headers: { 'Content-Disposition': `attachment; filename="demo-export.xlsx"` },
      });
    }
    if (r.method === 'POST' && r.url.pathname.includes('/excel/demo-import')) {
      return importResponse ? importResponse() : envelope(report);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

const importFile = () => new File([new Uint8Array([1, 2, 3])], 'rows.xlsx', { type: XLSX_TYPE });

describe('REQ20261114120101 useExcelExport / useExcelImport（AC-6 headless）', () => {
  it('should_fetch_export_url_and_trigger_download_when_export_called', async () => {
    const { fetchMock } = setupExcelApi();
    const h = await setupHook(() => useExcelExport());

    await act(async () => {
      await h.result.current.exportFile('/admin/v1/infra/excel/demo-export');
      await flush();
    });

    const gets = callsOf(fetchMock, 'GET', '/excel/demo-export');
    expect(gets).toHaveLength(1);
    // 触发下载：blob 对象 URL 已建立（jsdom 下下载机制的最小可观察面）
    expect(createObjectURLMock).toHaveBeenCalledTimes(1);
    h.unmount();
  });

  it('should_post_multipart_file_and_expose_import_report_when_import_succeeds', async () => {
    const { fetchMock } = setupExcelApi();
    const h = await setupHook(() => useExcelImport());

    await act(async () => {
      await h.result.current.importFile('/admin/v1/infra/excel/demo-import', importFile());
      await flush();
    });

    const posts = callsOf(fetchMock, 'POST', '/excel/demo-import');
    expect(posts).toHaveLength(1);
    // multipart 字段名 = file（071607 裁决⑦）
    const body = posts[0]?.body;
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBeTruthy();
    // ImportReport 形状锁定：total/valid/errors[{row,field,message}]
    expect(h.result.current.report).toMatchObject({
      total: 3,
      valid: 2,
      errors: [{ row: 3, field: '数量', message: '数量必须为非负数值' }],
    });
    h.unmount();
  });

  it('should_surface_error_with_code_when_import_file_unparseable', async () => {
    const { fetchMock } = setupExcelApi(() => failureEnvelope('EXCEL_PARSE_FAILED', 'EXCEL_PARSE_FAILED'));
    const h = await setupHook(() => useExcelImport());

    let caught: unknown = null;
    await act(async () => {
      try {
        await h.result.current.importFile('/admin/v1/infra/excel/demo-import', importFile());
      } catch (e) {
        caught = e;
      }
      await flush();
    });

    expect(callsOf(fetchMock, 'POST', '/excel/demo-import')).toHaveLength(1);
    // 失败可观察（抛出或 error 状态）：错误文本含既有错误码 EXCEL_PARSE_FAILED
    const text = `${errText(caught)} ${errText(h.result.current.error)}`;
    expect(text).toContain('EXCEL_PARSE_FAILED');
    h.unmount();
  });
});
