/** @vitest-environment jsdom */
/**
 * REQ-20261114-120103 AC-4/AC-5/AC-6：apiFetch 请求层加固（core headless 单测）。
 *
 *   AC-5 请求超时：缺省 15s（可按端点覆盖）→ 统一超时错误（fake timers 推进，委派单口径）。
 *   AC-6 GET 去重：相同 GET 并发→一次网络请求共享响应；query 不同/非 GET/非并发不去重
 *        （签名向后兼容面）。
 *   AC-4 AbortSignal 透传：外部 signal 传入 fetch 层、abort 后请求以错误收敛；
 *        401 单飞续约+重试回归不破坏（既有 apiClient 行为）。
 *
 * 装配口径：jsdom docblock（core barrel '../src' 拉 persist 型 store 需真实 localStorage）；
 * fetch 边界 mock 照 web/tests/meStore.test.ts（stubGlobal + Result 信封）；
 * 计数口径照 packages/core/tests/REQ20261114120101-useDict.test.ts（按弱匹配路由过滤 mock.calls）。
 * 定时器卫生照 REQ20261114120102-wsClient 教训：先 vi.restoreAllMocks() 再 vi.useRealTimers()。
 *
 * 【表面假定（REQ20261114120103 套件内连续编号）】
 *   A-7 apiFetch/useAuthStore 经 core barrel '../src' 导出（web '@acme/core' 等价面，
 *       web/tests/apiClient.test.ts 已证 apiFetch 导出）。
 *   A-8 扩展 init 形状：signal=标准 RequestInit.signal；per-request 覆盖键名=timeout（毫秒数字）。
 *       经 asInit() 以 Parameters<typeof apiFetch>[1] 断言面写入，类型检查对任意签名形状鲁棒。
 *   A-9 超时实现为可被 vitest fake timers 推进的定时器调度（setTimeout/AbortController，
 *       或可被伪造的 AbortSignal.timeout）；若实现走不可伪造的原生调度，本组用例失败即上报口径。
 *   A-10 401 单飞续约+重试（既有行为回归）：续约端点路径未契约化 → 以「非目标路径请求数」计数；
 *       续约响应形状未契约化 → fixture 供给双令牌 superset（accessToken/refreshToken/expiresAt/
 *       refreshExpiresAt，mobile auth 同族 wire 形状）。若 web 既有 401 语义为「清会话跳登录」
 *       而非续约重试，本组两用例按 TL 裁决调整。
 *
 * 【防串扰】各用例使用互不相同的请求路径，规避模块级去重/续约单飞缓存的跨用例命中。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, useAuthStore } from '../src';

vi.setConfig({ testTimeout: 15000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const unauthorized = () => new Response(
  JSON.stringify({ success: false, code: 'UNAUTHORIZED', message: '令牌过期' }), { status: 401 },
);

/** 续约响应 fixture（A-10：双令牌 superset，兼容任意解析面）。 */
const renewalPayload = () => ({
  accessToken: 'access-2',
  refreshToken: 'refresh-2',
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  refreshExpiresAt: new Date(Date.now() + 12_096_000_000).toISOString(),
});

/** 扩展 init 写入面（A-8）：以 apiFetch 实际参数类型承接，兼容任意扩展键。 */
const asInit = (extra: Record<string, unknown>) =>
  extra as unknown as Parameters<typeof apiFetch>[1];

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asUrl = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' || input instanceof URL
    ? new URL(String(input), 'http://localhost')
    : new URL((input as Request).url);
  return url;
};

const pathnamesOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[]).map(([input, init]) => asUrl(input, init).pathname);

const seedWebSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'expired-token',
    expiresAtMs: Date.now() + 1_800_000,
    userId: 1001,
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

afterEach(() => {
  // 定时器卫生（120102 wsClient 教训）：先还原探针再切回真实定时器，顺序不可反
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

// ————— AC-6：GET 去重（并发共享一次网络请求） —————
describe('REQ20261114120103 apiFetch GET 去重（AC-6）', () => {
  it('should_share_one_network_request_when_identical_gets_concurrent', async () => {
    const fetchMock = vi.fn(async () => envelope({ n: 1 }));
    vi.stubGlobal('fetch', fetchMock);

    const [a, b] = await Promise.all([
      apiFetch<unknown>('/admin/v1/system/tenants?page=1&dedup=a1'),
      apiFetch<unknown>('/admin/v1/system/tenants?page=1&dedup=a1'),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(a).toEqual({ n: 1 });
    expect(b).toEqual({ n: 1 });
  });

  it('should_fetch_separately_when_get_query_differs', async () => {
    const fetchMock = vi.fn(async () => envelope({ n: 1 }));
    vi.stubGlobal('fetch', fetchMock);

    await Promise.all([
      apiFetch<unknown>('/admin/v1/system/tenants?page=1&dedup=b1'),
      apiFetch<unknown>('/admin/v1/system/tenants?page=2&dedup=b1'),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('should_not_deduplicate_non_get_requests', async () => {
    const fetchMock = vi.fn(async () => envelope({ n: 1 }));
    vi.stubGlobal('fetch', fetchMock);

    await Promise.all([
      apiFetch<unknown>('/admin/v1/system/tenants', { method: 'POST', body: JSON.stringify({ a: 1 }) }),
      apiFetch<unknown>('/admin/v1/system/tenants', { method: 'POST', body: JSON.stringify({ a: 1 }) }),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('should_not_share_inflight_get_when_init_signal_aborted_by_first_caller', async () => {
    // StrictMode 双挂载时序：mount1 发请求 → mount2 并发同 URL → cleanup1 abort mount1 的 signal；
    // mount2 必须自有网络请求并成功，不得共享 mount1 被取消的在途 promise。
    const fetchMock = vi.fn((_input: unknown, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
      const signal = init?.signal;
      const abortError = () => new DOMException('The operation was aborted.', 'AbortError');
      if (signal?.aborted) {
        reject(abortError());
        return;
      }
      signal?.addEventListener('abort', () => reject(abortError()));
      setTimeout(() => resolve(envelope({ n: 1 })), 10);
    }));
    vi.stubGlobal('fetch', fetchMock);

    const first = new AbortController();
    const p1 = apiFetch<unknown>('/admin/v1/system/tenants?page=1&dedup=s1', { signal: first.signal });
    const p2 = apiFetch<unknown>('/admin/v1/system/tenants?page=1&dedup=s1', { signal: new AbortController().signal });
    first.abort();

    await expect(p1).rejects.toMatchObject({ name: 'AbortError' });
    await expect(p2).resolves.toEqual({ n: 1 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('should_fetch_fresh_when_previous_get_already_completed', async () => {
    const fetchMock = vi.fn(async () => envelope({ n: 1 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch<unknown>('/admin/v1/system/tenants?round=seq-1');
    await apiFetch<unknown>('/admin/v1/system/tenants?round=seq-1');

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

// ————— AC-5：请求超时（缺省 15s + per-request 覆盖） —————
describe('REQ20261114120103 apiFetch 超时（AC-5）', () => {
  it('should_reject_with_timeout_error_when_request_exceeds_default_15s', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {}))); // 挂起永不返回

    let settled = false;
    let err: unknown;
    const p = apiFetch<unknown>('/admin/v1/system/tenants?timeout=default')
      .then(() => { settled = true; }, (e) => { settled = true; err = e; });

    // 15s 缺省的收敛下界：14999ms 不得提前拒绝（A-9 fake timers 推进）
    await vi.advanceTimersByTimeAsync(14_999);
    expect(settled).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    await p;
    expect(settled).toBe(true);
    expect(err).toBeInstanceOf(Error); // 统一错误面（具体 code 未契约化，A-9/A-10 不硬编码）
  });

  it('should_timeout_early_when_per_request_override_provided', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})));

    let settled = false;
    let err: unknown;
    const p = apiFetch<unknown>('/admin/v1/system/tenants?timeout=override', asInit({ timeout: 50 }))
      .then(() => { settled = true; }, (e) => { settled = true; err = e; });

    await vi.advanceTimersByTimeAsync(49);
    expect(settled).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    await p;
    expect(settled).toBe(true);
    expect(err).toBeInstanceOf(Error);
  });
});

// ————— AC-4：AbortSignal 透传 —————
describe('REQ20261114120103 apiFetch AbortSignal 透传（AC-4）', () => {
  it('should_abort_in_flight_request_when_external_signal_aborted', async () => {
    const controller = new AbortController();
    const seenSignals: AbortSignal[] = [];
    // fetch 替身：捕获透传 signal，abort 即以 AbortError 收敛（真实 fetch 语义）
    vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      const signal = init?.signal;
      if (signal instanceof AbortSignal) seenSignals.push(signal);
      signal?.addEventListener('abort', () =>
        reject(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' })));
    })));

    const pending = apiFetch<unknown>('/admin/v1/system/tenants?abort=signal', asInit({ signal: controller.signal }));
    const captured = pending.catch((e) => e);
    controller.abort();
    const err = await captured;

    // 透传：fetch 层收到外部 AbortSignal 且 abort 状态传播到位
    expect(seenSignals.length).toBe(1);
    expect(seenSignals[0]?.aborted).toBe(true);
    // 取消收敛为错误（不悬挂）
    expect(err).toBeInstanceOf(Error);
  });
});

// ————— AC-4：401 单飞续约+重试（既有行为回归） —————
describe('REQ20261114120103 apiFetch 401 单飞续约+重试（AC-4 回归）', () => {
  it('should_retry_once_after_single_renewal_when_request_receives_401', async () => {
    seedWebSession();
    let dictCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const p = asUrl(input, init).pathname;
      if (p.includes('/system/dicts')) {
        dictCalls += 1;
        return dictCalls === 1 ? unauthorized() : envelope({ records: [], total: 0 });
      }
      return envelope(renewalPayload()); // 续约端点（路径未契约化，A-10）
    });
    vi.stubGlobal('fetch', fetchMock);

    const data = await apiFetch<unknown>('/admin/v1/system/dicts');

    expect(data).toEqual({ records: [], total: 0 });
    const paths = pathnamesOf(fetchMock);
    // 目标请求=原始+重试恰 2 次；续约恰 1 次（A-10）
    expect(paths.filter((p) => p.includes('/system/dicts')).length).toBe(2);
    expect(paths.filter((p) => !p.includes('/system/dicts')).length).toBe(1);
  });

  it('should_renew_single_flight_when_concurrent_requests_receive_401', async () => {
    seedWebSession();
    let dictCalls = 0;
    let menuCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const p = asUrl(input, init).pathname;
      if (p.includes('/system/dicts')) {
        dictCalls += 1;
        return dictCalls === 1 ? unauthorized() : envelope({ records: [], total: 0 });
      }
      if (p.includes('/system/menus')) {
        menuCalls += 1;
        return menuCalls === 1 ? unauthorized() : envelope([]);
      }
      return envelope(renewalPayload()); // 续约端点（A-10）
    });
    vi.stubGlobal('fetch', fetchMock);

    const [a, b] = await Promise.all([
      apiFetch<unknown>('/admin/v1/system/dicts'),
      apiFetch<unknown>('/admin/v1/system/menus'),
    ]);

    expect(a).toEqual({ records: [], total: 0 });
    expect(b).toEqual([]);
    const paths = pathnamesOf(fetchMock);
    // 两请求并发 401 → 续约单飞恰 1 次；各自原始+重试恰 2 次
    expect(paths.filter((p) => p.includes('/system/dicts')).length).toBe(2);
    expect(paths.filter((p) => p.includes('/system/menus')).length).toBe(2);
    expect(paths.filter((p) => !p.includes('/system/dicts') && !p.includes('/system/menus')).length).toBe(1);
  });
});
