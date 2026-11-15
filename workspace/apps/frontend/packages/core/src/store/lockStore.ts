import { create } from 'zustand';
import { useAuthStore } from './authStore';

/**
 * 锁屏状态 store（REQ-20261114-120112，AC-4）：
 * - 本地会话口令校验（契约确认记录③：非后端校验，与后端会话无交互）；
 * - 口令 / 锁屏态持久到 sessionStorage（标签页级会话存储，键名版本化）；
 * - 闲置触发由 store 内部 setTimeout 调度（时长经 setIdleTimeoutMs 可配置，0=关闭）；
 * - 忘记口令 = 清除本地口令并清除登录会话（useAuthStore.clear）→ 重新登录路径。
 */

const PASSCODE_KEY = 'acme.lock.passcode.v1';
const LOCKED_KEY = 'acme.lock.locked.v1';

interface LockState {
  locked: boolean;
  passcode: string;
  lock: () => void;
  unlock: (code: string) => { ok: boolean; message?: string };
  setPasscode: (code: string) => void;
  forgotPasscode: () => void;
  setIdleTimeoutMs: (ms: number) => void;
  touch: () => void;
  stopIdleWatch: () => void;
}

let idleTimer: ReturnType<typeof setTimeout> | null = null;
let idleMs = 0;

function persistLocked(locked: boolean): void {
  try {
    if (locked) {
      window.sessionStorage.setItem(LOCKED_KEY, '1');
    } else {
      window.sessionStorage.removeItem(LOCKED_KEY);
    }
  } catch {
    // 存储不可用（隐私模式等）：仅保留内存态
  }
}

function readPersistedLocked(): boolean {
  try {
    return window.sessionStorage.getItem(LOCKED_KEY) === '1';
  } catch {
    return false;
  }
}

function persistPasscode(code: string): void {
  try {
    if (code) {
      window.sessionStorage.setItem(PASSCODE_KEY, code);
    } else {
      window.sessionStorage.removeItem(PASSCODE_KEY);
    }
  } catch {
    // 存储不可用：仅保留内存态
  }
}

function readPersistedPasscode(): string {
  try {
    return window.sessionStorage.getItem(PASSCODE_KEY) ?? '';
  } catch {
    return '';
  }
}

function disarmIdle(): void {
  if (idleTimer !== null) {
    clearTimeout(idleTimer);
    idleTimer = null;
  }
}

function armIdle(ms: number, onLock: () => void): void {
  disarmIdle();
  if (ms > 0 && Number.isFinite(ms)) {
    idleTimer = setTimeout(onLock, ms);
  }
}

export const useLockStore = create<LockState>()((set, get) => ({
  locked: readPersistedLocked(),
  passcode: readPersistedPasscode(),

  lock: () => {
    persistLocked(true);
    set({ locked: true });
  },

  unlock: (code) => {
    const { locked, passcode } = get();
    if (!locked) {
      return { ok: true };
    }
    if (!passcode) {
      return { ok: false, message: '尚未设置锁屏口令，请在设置中配置' };
    }
    if (code !== passcode) {
      return { ok: false, message: '锁屏口令不正确，请重新输入' };
    }
    persistLocked(false);
    set({ locked: false });
    return { ok: true };
  },

  setPasscode: (code) => {
    persistPasscode(code);
    set({ passcode: code });
  },

  forgotPasscode: () => {
    try {
      window.sessionStorage.removeItem(PASSCODE_KEY);
      window.sessionStorage.removeItem(LOCKED_KEY);
    } catch {
      // 存储不可用时忽略
    }
    disarmIdle();
    idleMs = 0;
    set({ locked: false, passcode: '' });
    // 忘记口令 = 清除登录会话 → 重新登录（契约确认记录③）
    useAuthStore.getState().clear();
  },

  setIdleTimeoutMs: (ms) => {
    idleMs = ms;
    armIdle(ms, () => get().lock());
  },

  touch: () => {
    if (idleMs > 0) {
      armIdle(idleMs, () => get().lock());
    }
  },

  stopIdleWatch: () => {
    disarmIdle();
    idleMs = 0;
  },
}));
