// @vitest-environment jsdom
/**
 * REQ-20261128-051442（P1-07/P2-16）AC-6/7/8：web msm notify handler 幂等/扇出/拒绝语义锁死。
 *
 * 契约口径（contracts/REQ-20261128-051442-error-code-unification.md AC-6~8 + 附录 A/B）：
 *   AC-6 publish 幂等：已 PUBLISHED 再 publish → 原状返回（status/version/publishAt 不变）且
 *        不重复扇出 message；非 PUBLISHED publish → PUBLISHED + 全员 message 扇出 + version+1
 *        + publishAt 刷新（对齐后端 NoticeUseCase 幂等原状返回 + fanoutToAllEnabledUsers）。
 *   AC-7 offline 幂等：非 PUBLISHED offline → 幂等原状返回（version 不变）；PUBLISHED offline
 *        → OFFLINE + version+1。
 *   AC-8 拒绝语义：PUBLISHED update 拒 `platform-system-08-0001`（HTTP 400 族，对齐后端
 *        BizException(SystemError.NOTICE_PUBLISHED)）；version 不匹配拒 `SYS-01-0003`
 *        （CONCURRENCY_CONFLICT）；不存在 noticeId delete 拒 `platform-system-08-0002`
 *        （对齐后端 get() orElseThrow，非幂等 empty）。
 *
 * MSW 接入口径（web/tests/REQ20261122023213-schedulerMswContract.test.ts 先例）：
 *   - 复用项目交付 handlers（src/mocks/handlers，与 dev mock 共用），不另写桩 handler；
 *   - msw/node setupServer + onUnhandledRequest: 'error' 钉死端点覆盖；
 *   - 直接以 fetch 驱动（端点/信封为契约锚点，不经 core api 包装层），业务失败断言信封
 *     code 精确匹配附录 A/B 全码字面量（防旧符号短码假绿）。
 *   - handler 内部 store 为模块级单例，状态跨用例残留：每用例经 POST 自建公告取新 id，
 *     不依赖种子行；扇出观测经站内信列表端点（GET /admin/v1/system/messages total）黑盒比对，
 *     禁读 mocks/data.ts。
 *
 * 隔离纪律：全部写面操作只落自建公告行（新 id），零种子行变更；站内信扇出为 store 内追加、
 *   无删除端点可回收，以「同一公告前后 total 差值」为断言口径，跨用例无交叉依赖。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const NOTICES = '/admin/v1/system/notices';
const MESSAGES = '/admin/v1/system/messages';

/** AC-8 钉死全码字面量（附录 A.3 SystemErrorCodes 08 公告段 / A.2 WebError）。 */
const CODE_NOTICE_PUBLISHED = 'platform-system-08-0001';
const CODE_NOTICE_NOT_FOUND = 'platform-system-08-0002';
const CODE_CONCURRENCY_CONFLICT = 'SYS-01-0003';

const server = setupServer(...handlers);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

// ————— 支撑（fetch 直驱 + 信封拆包，schedulerMswContract 范本口径） —————

interface Envelope {
  success: boolean;
  code: string;
  message: string | null;
  data: unknown;
}

type Row = Record<string, unknown>;

const call = async (path: string, init?: RequestInit): Promise<{ res: Response; body: Envelope }> => {
  // 原生 fetch 不解析相对 URL；补 jsdom origin 使 msw 相对路径 handler 命中同 origin
  const res = await fetch(new URL(path, window.location.origin), {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-20261128051442', ...init?.headers },
  });
  const text = await res.text();
  // 空响应体按成功/失败信封兜底；JSON 解析失败即真错误，不吞
  const body = text.trim()
    ? JSON.parse(text) as Envelope
    : { success: res.ok, code: res.ok ? '0' : 'UNKNOWN', message: null, data: null };
  return { res, body };
};

const getJson = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path);

const postJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'POST', body: JSON.stringify(payload) });

const putJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'PUT', body: JSON.stringify(payload) });

const del = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path, { method: 'DELETE' });

/** 未知值 → 非空串（null 归 ''），wire 字段宽容读取。 */
const str = (v: unknown): string => (v == null ? '' : String(v));
/** version 宽容数值化（wire '0' 字符串 / number 双口径）。 */
const num = (v: unknown): number => Number(str(v));

const recordsOf = (data: unknown): Row[] => {
  expect(data, '分页 data 应为对象').toBeTypeOf('object');
  const page = data as { records?: unknown };
  expect(Array.isArray(page.records), '分页 data.records 应为数组').toBe(true);
  return page.records as Row[];
};

const listNotices = async (): Promise<Row[]> => {
  const { body } = await getJson(`${NOTICES}?page=1&size=50`);
  expect(body.success, '公告列表业务成功').toBe(true);
  expect(body.code).toBe('0');
  return recordsOf(body.data);
};

/** 列表回读定位公告行（读面为钉死稳定锚点，响应体形状不作断言面）。 */
const noticeOf = async (id: string): Promise<Row> => {
  const row = (await listNotices()).find((item) => str(item.id) === id);
  expect(row, `公告 ${id} 应在列表回读中`).toBeTruthy();
  return row as Row;
};

/** 站内信扇出观测面：GET /admin/v1/system/messages 分页 total。 */
const messagesTotal = async (): Promise<number> => {
  const { body } = await getJson(`${MESSAGES}?page=1&size=1`);
  expect(body.success, '站内信列表业务成功').toBe(true);
  expect(body.code).toBe('0');
  const page = body.data as { total?: unknown } | null;
  expect(typeof page?.total, '站内信分页 data.total 应为数值').toBe('number');
  return page?.total as number;
};

let seq = 0;
/** 每用例自建公告（POST 新 id，不依赖种子行）。 */
const createNotice = async (tag: string): Promise<Row> => {
  seq += 1;
  const title = `REQ051442-${tag}-${seq}`;
  const { body } = await postJson(NOTICES, { title, content: `<p>${title} 正文</p>` });
  expect(body.success, '公告创建业务成功').toBe(true);
  expect(body.code).toBe('0');
  const data = body.data as Row | null;
  expect(data, '创建应回显公告视图').toBeTruthy();
  expect(str(data?.id), '创建视图应携带 id').not.toBe('');
  return data as Row;
};

const publish = async (id: string): Promise<{ res: Response; body: Envelope }> =>
  putJson(`${NOTICES}/${id}/publish`, {});

const offline = async (id: string): Promise<{ res: Response; body: Envelope }> =>
  putJson(`${NOTICES}/${id}/offline`, {});

// ————— AC-6：publish 幂等与全员扇出语义 —————

describe('REQ20261128051442 notify publish 幂等与扇出（AC-6）', () => {
  it('should_fanout_messages_and_bump_version_when_publish_invoked_on_draft_notice', async () => {
    const beforeTotal = await messagesTotal();
    const created = await createNotice('pub-first');
    const id = str(created.id);
    expect(str(created.status), '新建公告应为 DRAFT（非 PUBLISHED 前置）').toBe('DRAFT');
    const draft = await noticeOf(id);
    const draftVersion = num(draft.version);
    expect(str(draft.publishAt), '草稿 publishAt 应为空').toBe('');

    const { body } = await publish(id);
    expect(body.success, '非 PUBLISHED publish 业务成功').toBe(true);
    expect(body.code).toBe('0');

    // 状态机：DRAFT → PUBLISHED + version+1 + publishAt 刷新
    const after = await noticeOf(id);
    expect(str(after.status), 'publish 应转 PUBLISHED').toBe('PUBLISHED');
    expect(num(after.version), 'publish 应 version+1').toBe(draftVersion + 1);
    expect(str(after.publishAt), 'publish 应刷新 publishAt').not.toBe('');

    // 扇出面：全员 message 批插 → 站内信列表 total 增长（黑盒比对）
    const afterTotal = await messagesTotal();
    expect(afterTotal, '发布应扇出全员 message（列表 total 增长）').toBeGreaterThan(beforeTotal);
  });

  it('should_keep_view_and_skip_refanout_when_publish_invoked_on_published_notice', async () => {
    const created = await createNotice('pub-idem');
    const id = str(created.id);
    // 前置：首次发布落 PUBLISHED（自建行，不依赖种子）
    await publish(id);
    const first = await noticeOf(id);
    expect(str(first.status), '前置：首次发布后应为 PUBLISHED').toBe('PUBLISHED');
    const totalAfterFirst = await messagesTotal();

    const { body } = await publish(id);
    expect(body.success, '重复 publish 应幂等成功（原状返回，非拒绝）').toBe(true);
    expect(body.code).toBe('0');

    // 幂等面：status/version/publishAt 三元原状
    const second = await noticeOf(id);
    expect(str(second.status), '重复 publish status 原状').toBe('PUBLISHED');
    expect(num(second.version), '重复 publish version 不变（不写库）').toBe(num(first.version));
    expect(str(second.publishAt), '重复 publish publishAt 不变').toBe(str(first.publishAt));

    // 扇出面：不得重复扇出 message
    const totalAfterSecond = await messagesTotal();
    expect(totalAfterSecond, '重复 publish 不得重复扇出 message').toBe(totalAfterFirst);
  });
});

// ————— AC-7：offline 幂等语义 —————

describe('REQ20261128051442 notify offline 幂等（AC-7）', () => {
  it('should_keep_status_and_version_when_offline_invoked_on_non_published_notice', async () => {
    const created = await createNotice('off-idem');
    const id = str(created.id);
    const draft = await noticeOf(id);
    const draftVersion = num(draft.version);
    expect(str(draft.status), '前置：自建公告为非 PUBLISHED').not.toBe('PUBLISHED');

    const { body } = await offline(id);
    expect(body.success, '非 PUBLISHED offline 应幂等成功（原状返回，非拒绝）').toBe(true);
    expect(body.code).toBe('0');

    const after = await noticeOf(id);
    expect(str(after.status), '非 PUBLISHED offline 原状（status 不变）').toBe(str(draft.status));
    expect(num(after.version), '非 PUBLISHED offline version 不变（不写库）').toBe(draftVersion);
    expect(str(after.publishAt), '非 PUBLISHED offline publishAt 原状').toBe(str(draft.publishAt));

    // 重复 offline 仍幂等
    const again = await offline(id);
    expect(again.body.success).toBe(true);
    const twice = await noticeOf(id);
    expect(num(twice.version), '重复 offline 仍不变 version').toBe(draftVersion);
  });

  it('should_transition_offline_and_bump_version_when_offline_invoked_on_published_notice', async () => {
    const created = await createNotice('off-pub');
    const id = str(created.id);
    await publish(id); // 前置：自建 PUBLISHED 行
    const published = await noticeOf(id);
    expect(str(published.status), '前置：发布后应为 PUBLISHED').toBe('PUBLISHED');
    const publishedVersion = num(published.version);

    const { body } = await offline(id);
    expect(body.success, 'PUBLISHED offline 业务成功').toBe(true);
    expect(body.code).toBe('0');

    const after = await noticeOf(id);
    expect(str(after.status), 'PUBLISHED offline 应转 OFFLINE').toBe('OFFLINE');
    expect(num(after.version), 'PUBLISHED offline 应 version+1').toBe(publishedVersion + 1);
  });
});

// ————— AC-8：update/delete 拒绝语义（全码字面量精确匹配） —————

describe('REQ20261128051442 notify update/delete 拒绝（AC-8）', () => {
  it('should_reject_update_with_notice_published_code_when_notice_is_published', async () => {
    const created = await createNotice('upd-pub');
    const id = str(created.id);
    await publish(id); // 前置：PUBLISHED
    const before = await noticeOf(id);
    expect(str(before.status)).toBe('PUBLISHED');

    const { res, body } = await putJson(`${NOTICES}/${id}`, {
      title: `${str(before.title)}-改`,
      content: '<p>REQ051442 改动正文</p>',
      version: before.version,
    });
    expect(body.success, 'PUBLISHED update 应拒绝').toBe(false);
    expect(body.code, 'AC-8 钉死全码字面量（附录 A.3 08-0001）').toBe(CODE_NOTICE_PUBLISHED);
    expect(res.status, '对齐后端 BizException 400 族（委派单钉死 HTTP 语义位）').toBeGreaterThanOrEqual(400);
    expect(res.status).toBeLessThanOrEqual(499);

    // 被拒更新不得覆盖有效状态
    const after = await noticeOf(id);
    expect(str(after.title), '被拒 update 不得改写标题').toBe(str(before.title));
    expect(str(after.status), '被拒 update 不得改写状态').toBe('PUBLISHED');
    expect(num(after.version), '被拒 update 不得改写 version').toBe(num(before.version));
  });

  it('should_reject_update_with_concurrency_conflict_code_when_version_mismatched', async () => {
    const created = await createNotice('upd-ver');
    const id = str(created.id);
    const before = await noticeOf(id); // DRAFT（避开 NOTICE_PUBLISHED 守卫，命中乐观锁校验）

    const { body } = await putJson(`${NOTICES}/${id}`, {
      title: `${str(before.title)}-改`,
      content: '<p>REQ051442 改动正文</p>',
      version: '999',
    });
    expect(body.success, 'version 不匹配 update 应拒绝').toBe(false);
    expect(body.code, 'AC-8 钉死乐观锁冲突全码（附录 A.2 SYS-01-0003）').toBe(CODE_CONCURRENCY_CONFLICT);

    // 被拒更新不得覆盖有效状态
    const after = await noticeOf(id);
    expect(str(after.title), '被拒 update 不得改写标题').toBe(str(before.title));
    expect(num(after.version), '被拒 update 不得改写 version').toBe(num(before.version));
  });

  it('should_reject_delete_with_notice_not_found_code_when_notice_id_missing', async () => {
    const missingId = `req051442-missing-${Date.now()}`;
    const { body } = await del(`${NOTICES}/${missingId}`);
    expect(body.success, '不存在 noticeId 删除应拒绝（对齐后端 orElseThrow，非幂等 empty）').toBe(false);
    expect(body.code, 'AC-8 钉死全码字面量（附录 A.3 08-0002）').toBe(CODE_NOTICE_NOT_FOUND);
  });
});
