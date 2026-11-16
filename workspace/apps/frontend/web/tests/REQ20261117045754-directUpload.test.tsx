/**
 * REQ-20261117-045754 对象存储直传（前端部分，AC-8 / AC-10）：
 * - UploadButton 主链路 = uploadDirect（preparation → PUT 原始字节 → completion → onUploaded 收 FileView）；
 * - 兜底兼容 = preparation 404/501（端点不存在）→ 回退既有 multipart uploadFormData；
 * - 直传错误码（配额 413 / 图片 400 / 票据 400）→ i18n 文案转译（files.errors.*，尾部附码）；
 * - api 层：fileDirectUploadPrepare / fileDirectUploadComplete 端点路径与 JSON 载荷口径；
 * - rawPut 认证口径：LOCAL 票据同源 URL 带 Authorization（既有认证面）；MINIO/OSS 预签名绝对
 *   URL 不带 Authorization（认证在签名 query，带 header 会与签名冲突）。
 *
 * 装配照既有范本 web/tests/REQ20261114120101-excelImportButton.test.tsx：
 * fetch stubGlobal 弱匹配 URL 路由 + useMeStore 播种（组件内查权限 fail-closed）+ <AntApp> 包裹 +
 * testTimeout ≥15000ms。PUT/POST 请求头经 init.headers（Headers 实例）断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { UploadButton } from '../src/components/UploadButton';
import { infraApi, uploadDirect, useAuthStore, useMeStore, type FileView } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// ————— 表面常量（契约 §API：preparations / completions / direct-uploads/{ticket}） —————
const MULTIPART_PATH = '/admin/v1/infra/files';
const PREPARE_PATH = '/admin/v1/infra/files/direct-upload-preparations';
const COMPLETE_PATH = '/admin/v1/infra/files/direct-upload-completions';
const TICKET_PATH_PART = '/direct-uploads/';
const TICKET_URL = '/admin/v1/infra/files/direct-uploads/ticket-1';
const MSG_QUOTA = /租户存储配额已超限/;
const MSG_IMAGE_INVALID = /图片无效或尺寸超出限制/;
const MSG_TICKET_INVALID = /直传票据已失效/;

// ————— fixtures（DirectUploadPreparationView / FileView 口径 = 契约 §API） —————
const preparation = {
  objectKey: 'files/acme/2026/11/17/ticket-1.bin',
  uploadUrl: TICKET_URL,
  method: 'PUT',
  headers: { 'Content-Type': 'text/plain' },
  expiresInSeconds: 300,
  ticket: 'ticket-1',
};

const fileView = {
  id: '9101',
  originalName: '直传成功.txt',
  size: 3,
  contentType: 'text/plain',
  url: null,
  bizType: null,
  bizId: null,
  createdAt: '2026-11-17T08:30:00Z',
};

const meView = {
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions: ['infra:file:upload'],
  menus: [],
};

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failEnvelope = (code: string, message: string, status = 200) => new Response(
  JSON.stringify({ success: false, code, message }), { status },
);

// ————— fetch 边界 mock —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), init };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), init };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string, exact = false) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === method && (exact ? r.url.pathname === pathPart : r.url.pathname.includes(pathPart)));

const headersOf = (call: { init?: RequestInit } | undefined): Headers => new Headers(call?.init?.headers);

const jsonBodyOf = (call: { init?: RequestInit } | undefined): Record<string, unknown> =>
  JSON.parse(String(call?.init?.body)) as Record<string, unknown>;

type DirectApiOptions = {
  /** preparation 返回非信封错误（404/501 = 老后端无直传端点，触发 multipart 回退）。 */
  prepareStatus?: number;
  /** preparation 业务失败码（信封；HTTP 状态由 prepareFailStatus 承载，缺省 200）。 */
  prepareFailCode?: string;
  prepareFailStatus?: number;
  /** 票据 PUT 业务失败码（信封 400）。 */
  putFailCode?: string;
};

const setupDirectApi = (opts: DirectApiOptions = {}) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (r.method === 'GET' && p.includes('/system/me')) {
      return envelope(meView);
    }
    if (r.method === 'POST' && p === PREPARE_PATH) {
      if (opts.prepareStatus) {
        return failEnvelope('NOT_FOUND', 'Not Found', opts.prepareStatus);
      }
      if (opts.prepareFailCode) {
        return failEnvelope(opts.prepareFailCode, opts.prepareFailCode, opts.prepareFailStatus);
      }
      return envelope(preparation);
    }
    if (r.method === 'PUT' && p.includes(TICKET_PATH_PART)) {
      if (opts.putFailCode) {
        return failEnvelope(opts.putFailCode, opts.putFailCode, 400);
      }
      return new Response(null, { status: 200 });
    }
    if (r.method === 'POST' && p === COMPLETE_PATH) {
      return envelope(fileView);
    }
    if (r.method === 'POST' && p === MULTIPART_PATH) {
      return envelope(fileView);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
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

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-direct-1',
    expiresAtMs: Date.now() + 10 * 60_000,
    userId: '1001',
    username: 'admin',
    nickname: '超级管理员',
    tenantCode: 'acme',
  }, false);
};

const renderButton = async (props: { onUploaded?: (file: FileView) => void } = {}) => {
  // 预热 me 画像（组件内查 infra:file:upload，fail-closed）
  await useMeStore.getState().load();
  render(<AntApp><UploadButton {...props} /></AntApp>);
};

const chooseFile = (name = '直传.txt', type = 'text/plain') => {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  expect(input).toBeTruthy();
  fireEvent.change(input, { target: { files: [new File([new Uint8Array([1, 2, 3])], name, { type })] } });
};

const makeFile = (name = '直传.txt', type = 'text/plain') =>
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
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261117045754 UploadButton 直传链（AC-8）', () => {
  it('should_upload_via_prepare_put_complete_chain_when_file_selected', async () => {
    const onUploaded = vi.fn();
    const { fetchMock } = setupDirectApi();
    await renderButton({ onUploaded });
    chooseFile('直传.txt', 'text/plain');

    await waitFor(() => expect(onUploaded).toHaveBeenCalledTimes(1));

    // preparation：JSON 载荷（fileName/contentType/size）
    const prepares = callsOf(fetchMock, 'POST', PREPARE_PATH, true);
    expect(prepares).toHaveLength(1);
    expect(headersOf(prepares[0]).get('Content-Type')).toBe('application/json');
    expect(jsonBodyOf(prepares[0])).toMatchObject({ fileName: '直传.txt', contentType: 'text/plain', size: 3 });

    // 票据 PUT：原始字节体（非 FormData）+ preparation Content-Type
    const puts = callsOf(fetchMock, 'PUT', TICKET_PATH_PART);
    expect(puts).toHaveLength(1);
    expect(puts[0]?.init?.body).toBeInstanceOf(File);
    expect(headersOf(puts[0]).get('Content-Type')).toBe('text/plain');

    // completion：objectKey 口径载荷
    const completes = callsOf(fetchMock, 'POST', COMPLETE_PATH, true);
    expect(completes).toHaveLength(1);
    expect(jsonBodyOf(completes[0])).toMatchObject({
      objectKey: preparation.objectKey,
      fileName: '直传.txt',
      contentType: 'text/plain',
      size: 3,
    });

    // onUploaded 收到 FileView（契约口径）+ 成功反馈
    expect(onUploaded.mock.calls[0]?.[0]).toMatchObject({ id: '9101', originalName: '直传成功.txt' });
    expect(await screen.findByText(/上传成功/)).toBeTruthy();
  });

  it('should_fallback_to_multipart_when_prepare_returns_404', async () => {
    const onUploaded = vi.fn();
    const { fetchMock } = setupDirectApi({ prepareStatus: 404 });
    await renderButton({ onUploaded });
    chooseFile();

    // preparation 404（端点不存在）→ 回退既有 multipart，onUploaded 行为不变
    await waitFor(() => expect(onUploaded).toHaveBeenCalledTimes(1));
    expect(callsOf(fetchMock, 'POST', PREPARE_PATH, true)).toHaveLength(1);
    const multiparts = callsOf(fetchMock, 'POST', MULTIPART_PATH, true);
    expect(multiparts).toHaveLength(1);
    expect((multiparts[0]?.init?.body as FormData).get('file')).toBeTruthy();
    expect(callsOf(fetchMock, 'PUT', TICKET_PATH_PART)).toHaveLength(0);
    expect(callsOf(fetchMock, 'POST', COMPLETE_PATH, true)).toHaveLength(0);
  });

  it('should_fallback_to_multipart_when_prepare_returns_501', async () => {
    const onUploaded = vi.fn();
    const { fetchMock } = setupDirectApi({ prepareStatus: 501 });
    await renderButton({ onUploaded });
    chooseFile();

    await waitFor(() => expect(onUploaded).toHaveBeenCalledTimes(1));
    expect(callsOf(fetchMock, 'POST', MULTIPART_PATH, true)).toHaveLength(1);
    expect(callsOf(fetchMock, 'POST', COMPLETE_PATH, true)).toHaveLength(0);
  });

  it('should_show_quota_message_when_prepare_rejects_with_quota_exceeded', async () => {
    const onUploaded = vi.fn();
    const { fetchMock } = setupDirectApi({ prepareFailCode: 'STORAGE_QUOTA_EXCEEDED', prepareFailStatus: 413 });
    await renderButton({ onUploaded });
    chooseFile();

    // 配额 413 信封 → i18n 配额文案 + 尾部附码（AC-10 映射口径）；不回退 multipart
    expect(await screen.findByText(MSG_QUOTA)).toBeTruthy();
    expect(screen.getByText(/STORAGE_QUOTA_EXCEEDED/)).toBeTruthy();
    expect(callsOf(fetchMock, 'POST', MULTIPART_PATH, true)).toHaveLength(0);
    expect(callsOf(fetchMock, 'POST', COMPLETE_PATH, true)).toHaveLength(0);
    expect(onUploaded).not.toHaveBeenCalled();
  });

  it('should_show_image_invalid_message_when_prepare_rejects_with_image_invalid', async () => {
    const { fetchMock } = setupDirectApi({ prepareFailCode: 'STORAGE_IMAGE_INVALID', prepareFailStatus: 400 });
    await renderButton();
    chooseFile('坏图.png', 'image/png');

    // 图片语义 400 信封 → i18n 图片文案 + 尾部附码
    expect(await screen.findByText(MSG_IMAGE_INVALID)).toBeTruthy();
    expect(screen.getByText(/STORAGE_IMAGE_INVALID/)).toBeTruthy();
    expect(callsOf(fetchMock, 'PUT', TICKET_PATH_PART)).toHaveLength(0);
  });

  it('should_show_ticket_message_when_ticket_put_rejected', async () => {
    const { fetchMock } = setupDirectApi({ putFailCode: 'TICKET_INVALID' });
    await renderButton();
    chooseFile();

    // 票据 PUT 400 信封 → ApiError 解析（信封错误码口径）→ 票据失效文案；不再走 completion
    expect(await screen.findByText(MSG_TICKET_INVALID)).toBeTruthy();
    expect(screen.getByText(/TICKET_INVALID/)).toBeTruthy();
    expect(callsOf(fetchMock, 'PUT', TICKET_PATH_PART)).toHaveLength(1);
    expect(callsOf(fetchMock, 'POST', COMPLETE_PATH, true)).toHaveLength(0);
  });
});

describe('REQ20261117045754 直传 api 层（AC-8 前端 api 面）', () => {
  it('should_post_prepare_endpoint_with_json_payload_when_fileDirectUploadPrepare_called', async () => {
    const { fetchMock } = setupDirectApi();
    const view = await infraApi.fileDirectUploadPrepare({
      fileName: 'a.png', contentType: 'image/png', size: 7, bizType: 'demo', bizId: '42',
    });
    expect(view).toMatchObject({ objectKey: preparation.objectKey, uploadUrl: TICKET_URL, ticket: 'ticket-1' });
    const calls = callsOf(fetchMock, 'POST', PREPARE_PATH, true);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url.pathname).toBe(PREPARE_PATH);
    expect(jsonBodyOf(calls[0])).toEqual({
      fileName: 'a.png', contentType: 'image/png', size: 7, bizType: 'demo', bizId: '42',
    });
  });

  it('should_post_complete_payload_with_object_key_when_fileDirectUploadComplete_called', async () => {
    const { fetchMock } = setupDirectApi();
    // mock 契约口径：completion 要求对象已 PUT（票据登记），api 层链路按 prepare → PUT → complete 复演
    const prepared = await infraApi.fileDirectUploadPrepare({ fileName: 'a.png', contentType: 'image/png', size: 3 });
    const putRes = await fetch(TICKET_URL, {
      method: 'PUT',
      body: new Uint8Array([1, 2, 3]),
      headers: { 'Content-Type': 'image/png' },
    });
    expect(putRes.ok).toBe(true);
    const view = await infraApi.fileDirectUploadComplete({
      objectKey: prepared.objectKey, fileName: 'a.png', contentType: 'image/png', size: 3,
    });
    expect(view).toMatchObject({ id: '9101' });
    const completes = callsOf(fetchMock, 'POST', COMPLETE_PATH, true);
    expect(completes).toHaveLength(1);
    expect(completes[0]?.url.pathname).toBe(COMPLETE_PATH);
    expect(jsonBodyOf(completes[0])).toMatchObject({ objectKey: prepared.objectKey, size: 3 });
  });
});

describe('REQ20261117045754 rawPut 认证口径', () => {
  it('should_send_authorization_header_when_put_same_origin_ticket_url', async () => {
    seedSession();
    const { fetchMock } = setupDirectApi();
    const view = await uploadDirect(makeFile());
    expect(view).toMatchObject({ id: '9101' });
    const puts = callsOf(fetchMock, 'PUT', TICKET_PATH_PART);
    expect(puts).toHaveLength(1);
    // LOCAL 票据端点 = 同源 /admin/… 走既有认证面：带 Authorization + preparation Content-Type
    const headers = headersOf(puts[0]);
    expect(headers.get('Authorization')).toBe('Bearer token-direct-1');
    expect(headers.get('Content-Type')).toBe('text/plain');
  });

  it('should_not_send_authorization_header_when_put_presigned_absolute_url', async () => {
    seedSession();
    const presigned = {
      ...preparation,
      uploadUrl: 'https://storage.example.com/acme-bucket/files/acme/2026/11/17/x.bin?X-Amz-Signature=abc',
      ticket: null,
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.method === 'POST' && r.url.pathname === PREPARE_PATH) {
        return envelope(presigned);
      }
      if (r.method === 'PUT') {
        return new Response(null, { status: 200 });
      }
      if (r.method === 'POST' && r.url.pathname === COMPLETE_PATH) {
        return envelope(fileView);
      }
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);

    await uploadDirect(makeFile());
    const puts = (fetchMock.mock.calls as unknown as FetchArgs[])
      .map(([input, init]) => asRequest(input, init))
      .filter((r) => r.method === 'PUT');
    expect(puts).toHaveLength(1);
    // MINIO/OSS 预签名 URL = 绝对外域：不带 Authorization（认证在签名 query）
    expect(puts[0]?.url.origin).toBe('https://storage.example.com');
    const headers = headersOf(puts[0]);
    expect(headers.get('Authorization')).toBeNull();
    expect(headers.get('Content-Type')).toBe('text/plain');
  });
});
