import { http, HttpResponse } from 'msw';
import * as db from '../data';
import { bodyOf, empty, excelBinary, fail, ok, page, pagingOf, slicePage, urlOf } from '../utils';

/** 基础设施域 handler：文件服务 / Excel demo 导入导出 / 审计事件查询导出 / 运行异常三段。
 *  resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。 */

const store = {
  files: [...db.files],
  auditEvents: [...db.auditEvents],
  opExceptions: [...db.opExceptions],
};

let nextId = 5800;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();

/** 直传票据内存态（REQ-20261117-045754 mock：preparations 登记 → PUT 单次消费 → completions 幂等落库）。 */
interface PendingDirectUpload {
  ticket: string;
  objectKey: string;
  fileName: string;
  contentType: string;
  size: number;
  receivedBytes: number | null;
  bizType: string | null;
  bizId: string | null;
}

let ticketSeq = 9100;
const pendingDirectUploads = new Map<string, PendingDirectUpload>();
/** 已完成落库的直传对象（幂等口径：同 objectKey 未删行直接返回既有 FileView）。 */
const completedDirectObjects = new Map<string, Record<string, unknown>>();

/** Excel demo 导出/模板（binary 流 + Content-Disposition）：出参构造收敛至 utils.excelBinary。 */

export const infraHandlers = [
  http.get('/admin/v1/infra/files', ({ request }) => {
    const params = urlOf(request).searchParams;
    const keyword = params.get('keyword');
    const rows = keyword
      ? store.files.filter((f) => f.originalName.toLowerCase().includes(keyword.toLowerCase()))
      : store.files;
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/infra/files', async ({ request }) => {
    const form = await request.formData();
    const file = form.get('file');
    const created = {
      id: genId(),
      originalName: file instanceof File ? file.name : 'upload.bin',
      size: file instanceof File ? file.size : 0,
      contentType: file instanceof File ? (file.type || 'application/octet-stream') : 'application/octet-stream',
      url: null,
      bizType: (form.get('bizType') as string | null) ?? null,
      bizId: (form.get('bizId') as string | null) ?? null,
      createdAt: nowIso(),
    };
    store.files = [created, ...store.files];
    return ok(created);
  }),
  http.delete('/admin/v1/infra/files/:fileId', ({ params }) => {
    const id = String(params.fileId);
    store.files = store.files.filter((f) => f.id !== id);
    return empty();
  }),
  // ————— 直传三段（REQ-20261117-045754，AC-10）：preparations → 票据 PUT → completions —————
  http.post('/admin/v1/infra/files/direct-upload-preparations', async ({ request }) => {
    const body = await bodyOf(request);
    const fileName = typeof body.fileName === 'string' && body.fileName ? body.fileName : 'upload.bin';
    const contentType =
      typeof body.contentType === 'string' && body.contentType ? body.contentType : 'application/octet-stream';
    const size = typeof body.size === 'number' ? body.size : 0;
    // 错误分支（供测试/演示）：文件名含 quota-exceeded 触发配额 413 信封
    if (fileName.includes('quota-exceeded')) {
      return fail('STORAGE_QUOTA_EXCEEDED', '租户存储配额已超限', 413);
    }
    // 错误分支（供测试/演示）：image/* 且文件名含 broken-image 触发图片语义 400 信封
    // （REQ-20261128-051442：STORAGE_IMAGE_INVALID 杜撰前缀码纠正为附录 A.7 裸字面量 IMAGE_INVALID）
    if (contentType.startsWith('image/') && fileName.includes('broken-image')) {
      return fail('IMAGE_INVALID', '图片无效或尺寸超出限制', 400);
    }
    const ticket = `ticket-${ticketSeq++}`;
    const pending: PendingDirectUpload = {
      ticket,
      objectKey: `files/acme/2026/11/17/${ticket}.bin`,
      fileName,
      contentType,
      size,
      receivedBytes: null,
      bizType: typeof body.bizType === 'string' ? body.bizType : null,
      bizId: typeof body.bizId === 'string' ? body.bizId : null,
    };
    pendingDirectUploads.set(ticket, pending);
    // LOCAL 票据形 URL（同源 /admin/…，需带 Authorization）
    return ok({
      objectKey: pending.objectKey,
      uploadUrl: `/admin/v1/infra/files/direct-uploads/${ticket}`,
      method: 'PUT',
      headers: { 'Content-Type': contentType },
      expiresInSeconds: 300,
      ticket,
    });
  }),
  http.put('/admin/v1/infra/files/direct-uploads/:ticket', async ({ request, params }) => {
    const ticket = String(params.ticket);
    const pending = pendingDirectUploads.get(ticket);
    if (!pending) {
      // 票据失效/已消费/未知 → TICKET_INVALID（单次消费 getdel 语义）
      return fail('TICKET_INVALID', '直传票据已失效', 400);
    }
    const raw = await request.arrayBuffer();
    pending.receivedBytes = raw.byteLength;
    return new HttpResponse(null, { status: 200 });
  }),
  http.post('/admin/v1/infra/files/direct-upload-completions', async ({ request }) => {
    const body = await bodyOf(request);
    const objectKey = typeof body.objectKey === 'string' ? body.objectKey : '';
    const size = typeof body.size === 'number' ? body.size : -1;
    const pending = [...pendingDirectUploads.values()].find((p) => p.objectKey === objectKey);
    if (!pending || pending.receivedBytes === null) {
      return fail('STORAGE_OBJECT_MISSING', '存储对象不存在', 404);
    }
    if (pending.size !== size || pending.receivedBytes !== pending.size) {
      return fail('SIZE_MISMATCH', '文件大小与直传记录不一致', 400);
    }
    const existing = completedDirectObjects.get(objectKey);
    if (existing) {
      return ok(existing); // 幂等：同 objectKey 未删行直接返回既有 FileView
    }
    const created = {
      id: genId(),
      originalName: pending.fileName,
      size: pending.size,
      contentType: pending.contentType,
      url: null,
      bizType: pending.bizType,
      bizId: pending.bizId,
      createdAt: nowIso(),
    };
    store.files = [created, ...store.files];
    completedDirectObjects.set(objectKey, created);
    pendingDirectUploads.delete(pending.ticket);
    return ok(created);
  }),
  http.get('/admin/v1/infra/files/:fileId/content', ({ params }) => {
    const target = store.files.find((f) => f.id === String(params.fileId));
    return new HttpResponse('mock-file-content', {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${target?.originalName ?? 'download.bin'}"`,
      },
    });
  }),
  // 文件详情（REQ-20261124-213008 契约②；权限点 infra:file:list；不存在 → FILE_NOT_FOUND）：
  // 与 :fileId/content 全路径匹配互不冲突（:fileId 单段，不含后续段）
  http.get('/admin/v1/infra/files/:fileId', ({ params }) => {
    const target = store.files.find((f) => f.id === String(params.fileId));
    if (!target) {
      return fail('FILE_NOT_FOUND', '文件不存在或已被删除', 404);
    }
    return ok(target);
  }),
  http.get('/admin/v1/infra/excel/demo-template', () => excelBinary('import-template.xlsx')),
  http.get('/admin/v1/infra/excel/demo-export', () => excelBinary('demo-export.xlsx')),
  http.post('/admin/v1/infra/excel/demo-import', () => ok({ total: 2, valid: 2, errors: [] })),
  http.get('/admin/v1/infra/audit-events', ({ request }) => {
    const params = urlOf(request).searchParams;
    const rows = store.auditEvents.filter((event) => {
      if (params.get('operatorId') && event.operatorId !== params.get('operatorId')) return false;
      if (params.get('action') && !event.action.includes(params.get('action')!)) return false;
      if (params.get('traceId') && !event.traceId.includes(params.get('traceId')!)) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  // ————— 运行异常三段（REQ-20261117-061017）：导出 / 列表 / 详情（export 先注册，避免被 :exceptionId 吞并） —————
  http.get('/admin/v1/infra/op-exceptions/export', () => excelBinary('op-exceptions.xlsx')),
  http.get('/admin/v1/infra/op-exceptions', ({ request }) => {
    const params = urlOf(request).searchParams;
    const rows = store.opExceptions.filter((row) => {
      if (params.get('service') && row.service !== params.get('service')) return false;
      if (params.get('traceId') && !row.traceId.includes(params.get('traceId')!)) return false;
      if (params.get('exceptionType') && !row.exceptionType.includes(params.get('exceptionType')!)) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.get('/admin/v1/infra/op-exceptions/:exceptionId', ({ params }) => {
    const target = store.opExceptions.find((row) => row.id === String(params.exceptionId));
    if (!target) {
      return fail('OP_EXCEPTION_NOT_FOUND', '异常记录不存在', 404);
    }
    return ok(target);
  }),
  // ————— 审计事件导出（REQ-20261117-061017）：xlsx 附件（豁免信封；上限/截断头在后端） —————
  http.get('/admin/v1/infra/audit-events/export', () => excelBinary('audit-events.xlsx')),
];
