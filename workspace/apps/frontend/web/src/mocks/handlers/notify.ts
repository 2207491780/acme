import { http } from 'msw';
import * as db from '../data';
import { bodyOf, empty, fail, ok, page, pagingOf, slicePage, urlOf } from '../utils';

/** 通知域 handler（REQ-20261114-120102 口径）：公告 CRUD/发布/下线 + 站内信收件箱/未读数/已读。
 *  resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。
 *  REQ-20261128-051442（P2-16 语义对齐）：错误码升附录 A.3 全码真值
 *  （NOTICE_PUBLISHED=platform-system-08-0001 / NOTICE_NOT_FOUND=platform-system-08-0002），
 *  且状态机对齐后端 NoticeUseCase：publish/offline 幂等原状返回（不重复扇出/不重复 +version）、
 *  update 拒 PUBLISHED（08-0001，400 族）+ version 冲突=SYS-01-0003（409）、
 *  delete 查无非幂等（08-0002，400 族）、publish 扇出全员 message（对齐 fanoutToAllEnabledUsers）。 */

const store = {
  notices: [...db.notices],
  messages: [...db.messages],
};

let nextId = 9500;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();
const keywordOf = (request: Request): string | null => urlOf(request).searchParams.get('keyword');

/** 扇出全员站内信（对齐后端 fanoutToAllEnabledUsers 批插）：每个启用用户一行 AdminMessageView
 *  （senderId '0'=系统；标题/正文取公告）。mock 收件箱无收件人过滤列，行集即全员扇出落库面。 */
function fanoutNoticeToAllUsers(notice: { title: string; content: string }): void {
  const enabledCount = db.users.filter((user) => user.status === 'ENABLED').length;
  const rows = Array.from({ length: enabledCount }, () => ({
    id: genId(),
    senderId: '0',
    title: notice.title,
    content: notice.content,
    isRead: false,
    readAt: null,
    createdAt: nowIso(),
    version: '0',
  }));
  store.messages = [...rows, ...store.messages];
}

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
      return fail('platform-system-08-0002', '公告不存在或已被删除', 404);
    }
    // P2-16 语义对齐（AC-8）：PUBLISHED 拒编辑（对齐后端 NoticeUseCase :79-81 抛 NOTICE_PUBLISHED，
    // BizException → 400 族）；version 乐观锁冲突 = SYS-01-0003（409，对齐 CONCURRENCY_CONFLICT）。
    if (target.status === 'PUBLISHED') {
      return fail('platform-system-08-0001', '公告已发布，需先下线后再操作', 400);
    }
    if (body.version !== undefined && body.version !== null && String(body.version) !== target.version) {
      return fail('SYS-01-0003', '公告已被其他用户修改，请刷新后重试', 409);
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
  // publish（P2-16/AC-6）：已 PUBLISHED → 幂等原状返回（status/version/publishAt 不变、不重复扇出）；
  // 非 PUBLISHED（DRAFT/OFFLINE，OFFLINE 再发布=新一轮扇出）→ 转 PUBLISHED + version+1 +
  // publishAt 刷新 + 扇出全员 message。
  http.put('/admin/v1/system/notices/:noticeId/publish', ({ params }) => {
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return fail('platform-system-08-0002', '公告不存在或已被删除', 404);
    }
    if (target.status === 'PUBLISHED') {
      return ok(target);
    }
    const updated = { ...target, status: 'PUBLISHED' as const, publishAt: nowIso(), version: String(Number(target.version) + 1) };
    store.notices = store.notices.map((n) => (n.id === id ? updated : n));
    fanoutNoticeToAllUsers(updated);
    return ok(updated);
  }),
  // offline（P2-16/AC-7）：非 PUBLISHED → 幂等原状返回（version 不变）；PUBLISHED → OFFLINE + version+1。
  http.put('/admin/v1/system/notices/:noticeId/offline', ({ params }) => {
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return fail('platform-system-08-0002', '公告不存在或已被删除', 404);
    }
    if (target.status !== 'PUBLISHED') {
      return ok(target);
    }
    const updated = { ...target, status: 'OFFLINE' as const, version: String(Number(target.version) + 1) };
    store.notices = store.notices.map((n) => (n.id === id ? updated : n));
    return ok(updated);
  }),
  // delete（P2-16/AC-8）：查无 → 非幂等拒绝（08-0002，对齐后端 get() orElseThrow，BizException 400 族）；
  // PUBLISHED 拒删（08-0001，400 族）；其余删除成功。
  http.delete('/admin/v1/system/notices/:noticeId', ({ params }) => {
    const id = String(params.noticeId);
    const target = store.notices.find((n) => n.id === id);
    if (!target) {
      return fail('platform-system-08-0002', '公告不存在或已被删除', 400);
    }
    if (target.status === 'PUBLISHED') {
      return fail('platform-system-08-0001', '公告已发布，需先下线后再操作', 400);
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
