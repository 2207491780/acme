import { http, HttpResponse, type DefaultBodyType } from 'msw';
import * as db from '../data';
import { empty, ok, page, pagingOf, slicePage, urlOf } from '../utils';

/** 基础设施域 handler：文件服务 / Excel demo 导入导出 / 审计事件查询。
 *  resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。 */

const store = { files: [...db.files], auditEvents: [...db.auditEvents] };

let nextId = 5800;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();

/** Excel demo 导出/模板（binary 流 + Content-Disposition）。 */
const excelBinary = (filename: string): HttpResponse<DefaultBodyType> =>
  new HttpResponse('PK\x03\x04mock-xlsx', {
    headers: {
      'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'Content-Disposition': `attachment; filename="${filename}"`,
    },
  });

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
  http.get('/admin/v1/infra/files/:fileId/content', ({ params }) => {
    const target = store.files.find((f) => f.id === String(params.fileId));
    return new HttpResponse('mock-file-content', {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${target?.originalName ?? 'download.bin'}"`,
      },
    });
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
];
