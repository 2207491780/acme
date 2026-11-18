import { http } from 'msw';
import * as db from '../data';
import { bodyOf, empty, fail, ok, page, pagingOf, slicePage, urlOf } from '../utils';

/** 通知域 handler（REQ-20261114-120102 口径）：公告 CRUD/发布/下线 + 站内信收件箱/未读数/已读。
 *  resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。 */

const store = {
  notices: [...db.notices],
  messages: [...db.messages],
};

let nextId = 9500;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();
const keywordOf = (request: Request): string | null => urlOf(request).searchParams.get('keyword');

export const notifyHandlers = [
  http.get('/admin/v1/system/notices', ({ request }) => {
    const keyword = keywordOf(request);
    const rows = keyword
      ? store.notices.filter((n) => n.title.toLowerCase().includes(keyword.toLowerCase()))
      : store.notices;
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/notices', async ({ request }) => {
    const body = await bodyOf(request);
    const created = {
      id: genId(),
      title: String(body.title ?? ''),
      content: String(body.content ?? ''),
      status: 'DRAFT' as const,
      publishAt: null,
      createdAt: nowIso(),
      updatedAt: nowIso(),
      version: '0',
    };
    store.notices = [created, ...store.notices];
    return ok(created);
  }),
  http.put('/admin/v1/system/notices/:noticeId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return fail('NOTICE_NOT_FOUND', '公告不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      title: typeof body.title === 'string' ? body.title : target.title,
      content: typeof body.content === 'string' ? body.content : target.content,
      updatedAt: nowIso(),
      version: String(Number(target.version) + 1),
    };
    store.notices = store.notices.map((n) => (n.id === id ? updated : n));
    return ok(updated);
  }),
  http.put('/admin/v1/system/notices/:noticeId/publish', ({ params }) => {
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return fail('NOTICE_NOT_FOUND', '公告不存在或已被删除', 404);
    }
    const updated = { ...target, status: 'PUBLISHED' as const, publishAt: nowIso(), version: String(Number(target.version) + 1) };
    store.notices = store.notices.map((n) => (n.id === id ? updated : n));
    return ok(updated);
  }),
  http.put('/admin/v1/system/notices/:noticeId/offline', ({ params }) => {
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return fail('NOTICE_NOT_FOUND', '公告不存在或已被删除', 404);
    }
    const updated = { ...target, status: 'OFFLINE' as const, version: String(Number(target.version) + 1) };
    store.notices = store.notices.map((n) => (n.id === id ? updated : n));
    return ok(updated);
  }),
  http.delete('/admin/v1/system/notices/:noticeId', ({ params }) => {
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return empty();
    }
    if (target.status === 'PUBLISHED') {
      return fail('NOTICE_PUBLISHED', '公告已发布，需先下线后再操作', 400);
    }
    store.notices = store.notices.filter((n) => n.id !== id);
    return empty();
  }),

  http.get('/admin/v1/system/messages', ({ request }) => {
    const params = urlOf(request).searchParams;
    const isRead = params.get('isRead');
    const rows = store.messages.filter((m) => isRead === null || String(m.isRead) === isRead);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.get('/admin/v1/system/messages/unread-count', () => ok(store.messages.filter((m) => !m.isRead).length)),
  http.put('/admin/v1/system/messages/read-all', () => {
    store.messages = store.messages.map((m) => ({ ...m, isRead: true, readAt: nowIso() }));
    return empty();
  }),
  http.put('/admin/v1/system/messages/:messageId/read', ({ params }) => {
    const id = String(params.messageId);
    store.messages = store.messages.map((m) => (m.id === id ? { ...m, isRead: true, readAt: nowIso() } : m));
    return empty();
  }),
];
