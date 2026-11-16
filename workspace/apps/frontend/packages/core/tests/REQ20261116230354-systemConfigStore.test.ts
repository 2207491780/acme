// @vitest-environment jsdom
/**
 * REQ-20261116-230354 core 契约用例：useSystemConfigStore（load/update + 副作用接线）
 * 与 useLockStore 既有行为回归（AC-9）。
 *
 * 命名/字段/方法以契约§变更清单「命名裁定」为准：useSystemConfigStore 状态
 * watermarkEnabled/lockIdleTimeoutMs/version/loaded，方法 load()/update(payload)；
 * systemApi.configGet()/configUpdate(payload)；视图 AdminSystemConfigView。
 *
 * lockStore 口径 = TL 回填契约确认记录⑨（实际实现口径，勿再假定）：
 *   状态仅 locked: boolean / passcode: string（''=未设置）；闲置时长为模块内部私有变量，
 *   无公开字段——AC-8 用「spy setIdleTimeoutMs 接线断言 + fakeTimers 行为断言」组合。
 *   lock() 无条件置 locked=true；unlock(code) 返回 { ok, message? }（未锁→ok:true；
 *   已锁无口令→ok:false '尚未设置锁屏口令，请在设置中配置'；口令错→ok:false '锁屏口令不正确，请重新输入'；
 *   口令对→ok:true 且解锁）；forgotPasscode() 清口令+解锁+useAuthStore.clear()；
 *   setIdleTimeoutMs(ms) 武装定时器（0=解除），到期内部调 lock()；touch() 重置计时；stopIdleWatch() 停止。
 *   sessionStorage 键：acme.lock.passcode.v1 / acme.lock.locked.v1。
 *
 * 业务失败口径：HTTP 200 信封 { success:false, code≠'0' }；update 失败抛错由页面提示（契约钉死）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { systemApi, useAuthStore, useLockStore, useSystemConfigStore } from '../src';
import type { AdminSystemConfigView } from '../src';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (message: string) => new Response(
  JSON.stringify({ success: false, code: 'platform-system-08-0003', message, data: null }), { status: 200 },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes('/system/config') && r.method === method);

/** 有状态 config 桩：GET 回当前值；PUT 校验 version（不匹配→业务失败信封），成功 version+1。 */
const setupConfigApi = (initial: AdminSystemConfigView) => {
  const state = { ...initial };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes('/system/config')) return envelope(null);
    if (r.method === 'GET') return envelope({ ...state });
    if (r.method === 'PUT') {
      const body = parseBody(r.body);
      if (String(body.version) !== state.version) {
        return failureEnvelope('数据已被他人修改，请刷新后重试');
      }
      state.watermarkEnabled = Boolean(body.watermarkEnabled);
      state.lockIdleTimeoutMs = Number(body.lockIdleTimeoutMs);
      state.version = String(Number(state.version) + 1);
      return envelope({ ...state });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const resetStores = () => {
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
  useLockStore.setState({ locked: false, passcode: '' });
};

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
});

afterEach(() => {
  // 共享环境脏数据 teardown：停闲置定时器 + 还原真实定时器 + 清 storage + store 复位
  useLockStore.getState().stopIdleWatch();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
  useAuthStore.getState().clear();
});

// ————— systemApi 契约端点 —————

describe('REQ20261116230354 systemApi 契约端点', () => {
  it('should_callGetAndPutConfigEndpoints_when_systemApiInvoked', async () => {
    const { fetchMock } = setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });

    const view = await systemApi.configGet();
    expect(view.watermarkEnabled).toBe(true);
    expect(view.lockIdleTimeoutMs).toBe(0);
    expect(view.version).toBe('0');
    expect(callsOf(fetchMock, 'GET')).toHaveLength(1);

    const updated = await systemApi.configUpdate({ watermarkEnabled: false, lockIdleTimeoutMs: 60000, version: '0' });
    expect(updated.version).toBe('1');
    const puts = callsOf(fetchMock, 'PUT');
    expect(puts).toHaveLength(1);
    expect(parseBody(puts[0]?.body)).toEqual({ watermarkEnabled: false, lockIdleTimeoutMs: 60000, version: '0' });
  });
});

// ————— useSystemConfigStore.load/update（AC-7 水印状态接线 / AC-5 持久化语义的 store 面） —————

describe('REQ20261116230354 useSystemConfigStore', () => {
  it('should_applyWatermarkState_when_loadReturnsConfig', async () => {
    setupConfigApi({ watermarkEnabled: false, lockIdleTimeoutMs: 60000, version: '3' });
    await useSystemConfigStore.getState().load();
    const s = useSystemConfigStore.getState();
    expect(s.loaded).toBe(true);
    expect(s.watermarkEnabled).toBe(false);
    expect(s.lockIdleTimeoutMs).toBe(60000);
    expect(s.version).toBe('3');

    setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '4' });
    await useSystemConfigStore.getState().load();
    expect(useSystemConfigStore.getState().watermarkEnabled).toBe(true);
  });

  it('should_updateStateAndReturnView_when_updateSucceeds', async () => {
    const { fetchMock } = setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });
    const view = await useSystemConfigStore.getState().update({ watermarkEnabled: false, lockIdleTimeoutMs: 300000, version: '0' });
    expect(view).toEqual({ watermarkEnabled: false, lockIdleTimeoutMs: 300000, version: '1' });
    const s = useSystemConfigStore.getState();
    expect(s.watermarkEnabled).toBe(false);
    expect(s.lockIdleTimeoutMs).toBe(300000);
    expect(s.version).toBe('1');
    expect(callsOf(fetchMock, 'PUT')).toHaveLength(1);
  });

  it('should_throwWithoutApplyingState_when_updateFailsWithBusinessError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => failureEnvelope('版本冲突')));
    await expect(
      useSystemConfigStore.getState().update({ watermarkEnabled: false, lockIdleTimeoutMs: 60000, version: '0' }),
    ).rejects.toBeTruthy();
    // 失败不得覆盖原状态
    const s = useSystemConfigStore.getState();
    expect(s.watermarkEnabled).toBe(true);
    expect(s.version).toBe('0');
  });
});

// ————— AC-8：load/update 副作用联动 lockStore.setIdleTimeoutMs（TL 确认记录⑨组合断言） —————

describe('REQ20261116230354 AC-8 闲置锁屏强制接线', () => {
  it('should_callSetIdleTimeoutMs_when_loadWithTenantTimeout', async () => {
    setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 60000, version: '0' });
    const spy = vi.spyOn(useLockStore.getState(), 'setIdleTimeoutMs');
    await useSystemConfigStore.getState().load();
    expect(spy).toHaveBeenCalledWith(60000);
  });

  it('should_lockAfterIdleDeadline_when_fakeTimersAdvance60s', async () => {
    setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 60000, version: '0' });
    vi.useFakeTimers();
    await useSystemConfigStore.getState().load();
    expect(useLockStore.getState().locked).toBe(false);
    vi.advanceTimersByTime(60000);
    expect(useLockStore.getState().locked).toBe(true);
  });

  it('should_notArmIdleWatch_when_timeoutZero', async () => {
    setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });
    vi.useFakeTimers();
    await useSystemConfigStore.getState().load();
    vi.advanceTimersByTime(900000);
    expect(useLockStore.getState().locked).toBe(false);
  });

  it('should_rearmIdleTimeout_when_updateChangesTimeout', async () => {
    setupConfigApi({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });
    vi.useFakeTimers();
    await useSystemConfigStore.getState().load();
    await useSystemConfigStore.getState().update({ watermarkEnabled: true, lockIdleTimeoutMs: 60000, version: '0' });
    vi.advanceTimersByTime(60000);
    expect(useLockStore.getState().locked).toBe(true);
  });
});

// ————— AC-9：既有锁屏流程回归（口令设置/锁屏/解锁/忘记口令，语义=TL 确认记录⑨） —————

describe('REQ20261116230354 AC-9 lockStore 回归', () => {
  it('should_lockAndUnlockWithPasscode_when_passcodeSet', () => {
    useLockStore.getState().setPasscode('1234');
    useLockStore.getState().lock();
    expect(useLockStore.getState().locked).toBe(true);

    const wrong = useLockStore.getState().unlock('wrong');
    expect(wrong.ok).toBe(false);
    expect(wrong.message).toBe('锁屏口令不正确，请重新输入');
    expect(useLockStore.getState().locked).toBe(true);

    const right = useLockStore.getState().unlock('1234');
    expect(right.ok).toBe(true);
    expect(useLockStore.getState().locked).toBe(false);
  });

  it('should_lockUnconditionally_when_lockWithoutPasscode', () => {
    useLockStore.getState().lock();
    expect(useLockStore.getState().locked).toBe(true);
    const result = useLockStore.getState().unlock('any');
    expect(result.ok).toBe(false);
    expect(result.message).toBe('尚未设置锁屏口令，请在设置中配置');
  });

  it('should_returnOk_when_unlockWhileNotLocked', () => {
    const result = useLockStore.getState().unlock('whatever');
    expect(result.ok).toBe(true);
  });

  it('should_clearPasscodeUnlockAndClearSession_when_forgotPasscode', () => {
    useAuthStore.getState().setSession({
      accessToken: 'token-1', expiresAtMs: Date.now() + 1_800_000,
      userId: 1001, username: 'admin', nickname: null, tenantCode: 'default',
    }, false);
    useLockStore.getState().setPasscode('1234');
    useLockStore.getState().lock();
    const clearSpy = vi.spyOn(useAuthStore.getState(), 'clear');

    useLockStore.getState().forgotPasscode();

    expect(useLockStore.getState().passcode).toBe('');
    expect(useLockStore.getState().locked).toBe(false);
    expect(clearSpy).toHaveBeenCalled();
  });

  it('should_persistPasscodeAndLockedToSessionStorage_when_setAndLock', () => {
    useLockStore.getState().setPasscode('1234');
    expect(window.sessionStorage.getItem('acme.lock.passcode.v1')).toContain('1234');
    useLockStore.getState().lock();
    expect(window.sessionStorage.getItem('acme.lock.locked.v1')).not.toBeNull();
  });

  it('should_disarmIdleWatch_when_setIdleTimeoutMsZero', () => {
    vi.useFakeTimers();
    useLockStore.getState().setIdleTimeoutMs(60000);
    useLockStore.getState().setIdleTimeoutMs(0);
    vi.advanceTimersByTime(900000);
    expect(useLockStore.getState().locked).toBe(false);
  });

  it('should_resetIdleTimer_when_touchCalled', () => {
    vi.useFakeTimers();
    useLockStore.getState().setIdleTimeoutMs(60000);
    vi.advanceTimersByTime(50000);
    useLockStore.getState().touch();
    vi.advanceTimersByTime(50000);
    expect(useLockStore.getState().locked).toBe(false);
    vi.advanceTimersByTime(10000);
    expect(useLockStore.getState().locked).toBe(true);
  });
});