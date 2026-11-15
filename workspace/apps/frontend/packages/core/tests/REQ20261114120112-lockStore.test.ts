/** @vitest-environment jsdom */
/**
 * REQ-20261114-120112 AC-4（core store 层）：锁屏状态 store——手动触发、闲置触发（时长可
 * 配置）、口令校验成功/失败提示、忘记口令→重新登录路径。
 *
 * 装配口径：core barrel '../src'（barrel 拉 persist 型 store 需真实 localStorage → jsdom
 * docblock，同 REQ20261114120103-apiFetch）；闲置触发为 store 内部定时器调度 → vitest
 * fake timers 推进；定时器卫生照 120102/120103 教训：先 restoreAllMocks 再 useRealTimers。
 *
 * 【表面假定（REQ20261114120112 套件内连续编号）】
 *   A-5 core barrel 命名导出 useLockStore（zustand；锁屏口令/状态持久到本地会话存储，
 *       键名未契约化 → 不断言具体键，仅断言行为；teardown 清两份 storage 防脏数据）。
 *   A-6 store 表面：state { locked: boolean }；actions：
 *       - lock()：手动上锁；
 *       - unlock(passcode) → { ok: boolean; message?: string }：本地会话口令校验
 *         （契约确认记录③：非后端校验；失败→locked 保持 true 且 message 非空）；
 *       - setPasscode(code)：设置本地会话口令；
 *       - forgotPasscode()：忘记口令 → 清除本地口令并清除登录会话（useAuthStore.clear，
 *         与 REQ20261114120103 范本同名面）→ 重新登录路径；
 *       - setIdleTimeoutMs(ms)：配置闲置触发时长并（重）武装闲置定时器；
 *       - touch()：活动打点（重置闲置计时并重武装）；
 *       - stopIdleWatch()：解除闲置监听（teardown 卫生用）。
 *   A-7 闲置触发由 store 内部 setTimeout 调度（可被 fake timers 推进）；闲置设置项与
 *       Settings 体系的接线由 TL 门禁对齐（本文件只锁 store 语义）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore, useLockStore } from '../src';

type LockApi = {
  locked: boolean;
  lock: () => void;
  unlock: (code: string) => { ok: boolean; message?: string } | boolean;
  setPasscode: (code: string) => void;
  forgotPasscode: () => void;
  setIdleTimeoutMs: (ms: number) => void;
  touch: () => void;
  stopIdleWatch?: () => void;
};

const lockApi = (): LockApi => useLockStore.getState() as unknown as LockApi;
const isLocked = () => Boolean(lockApi().locked);

const unlock = (code: string): { ok: boolean; message?: string } => {
  const out = lockApi().unlock(code);
  return typeof out === 'boolean' ? { ok: out } : out; // 布尔返回面容差（A-6）
};

const resetLockStore = () => {
  const api = lockApi();
  if (typeof api.stopIdleWatch === 'function') api.stopIdleWatch();
  (useLockStore.setState as unknown as (partial: Record<string, unknown>) => void)({ locked: false });
};

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: 1001,
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 会话在否（A-8：会话字段形状未契约化 → 常见字段探测）。 */
const hasSession = () => {
  const s = useAuthStore.getState() as unknown as Record<string, unknown>;
  return Boolean(s.session ?? s.accessToken ?? s.token);
};

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  resetLockStore();
});

afterEach(() => {
  // 定时器卫生（120102/120103 教训）：先还原探针再切回真实定时器，顺序不可反
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  resetLockStore();
});

// ————— 手动触发 + 口令校验 —————
describe('REQ20261114120112 锁屏 store 手动触发与口令校验（AC-4）', () => {
  it('should_lock_when_manual_lock_triggered', () => {
    lockApi().setPasscode('4242');
    lockApi().lock();
    expect(isLocked()).toBe(true);
  });

  it('should_unlock_when_passcode_correct', () => {
    lockApi().setPasscode('4242');
    lockApi().lock();
    const r = unlock('4242');
    expect(r.ok).toBe(true);
    expect(isLocked()).toBe(false);
  });

  it('should_stay_locked_with_error_when_passcode_wrong', () => {
    lockApi().setPasscode('4242');
    lockApi().lock();
    const r = unlock('0000');
    expect(r.ok).toBe(false);
    expect(r.message).toBeTruthy(); // 契约「口令错误提示」：文案未契约化 → 非空即达标
    expect(isLocked()).toBe(true); // 失败不解锁
  });

  it('should_clear_session_when_forgot_passcode', () => {
    seedSession();
    expect(hasSession()).toBe(true);
    lockApi().setPasscode('4242');
    lockApi().lock();
    lockApi().forgotPasscode(); // 忘记口令 → 重新登录路径（契约确认记录③）
    expect(hasSession()).toBe(false); // 会话清除 → 下次进入须重新登录
  });
});

// ————— 闲置触发（时长可配置） —————
describe('REQ20261114120112 锁屏 store 闲置触发（AC-4）', () => {
  it('should_lock_when_idle_timeout_elapsed', () => {
    vi.useFakeTimers();
    lockApi().setIdleTimeoutMs(5_000);
    vi.advanceTimersByTime(5_000);
    expect(isLocked()).toBe(true);
  });

  it('should_not_lock_when_idle_below_timeout', () => {
    vi.useFakeTimers();
    lockApi().setIdleTimeoutMs(5_000);
    vi.advanceTimersByTime(4_999);
    expect(isLocked()).toBe(false);
  });

  it('should_reset_idle_timer_when_activity_touched', () => {
    vi.useFakeTimers();
    lockApi().setIdleTimeoutMs(5_000);
    vi.advanceTimersByTime(4_000);
    lockApi().touch(); // 活动打点 → 计时重置
    vi.advanceTimersByTime(4_000);
    expect(isLocked()).toBe(false);
    vi.advanceTimersByTime(1_000); // 距上次活动满 5s
    expect(isLocked()).toBe(true);
  });

  it('should_respect_reconfigured_idle_timeout', () => {
    vi.useFakeTimers();
    lockApi().setIdleTimeoutMs(60_000);
    vi.advanceTimersByTime(10_000);
    expect(isLocked()).toBe(false); // 新时长未到不锁
    lockApi().setIdleTimeoutMs(5_000); // 重新配置（可配置口径）
    vi.advanceTimersByTime(5_000);
    expect(isLocked()).toBe(true);
  });
});
