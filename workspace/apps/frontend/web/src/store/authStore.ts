import { create } from 'zustand';

/** 登录会话（login-state：令牌 + 主体 + 租户；expiresAtMs 为本地 epoch 毫秒）。 */
export interface AuthSession {
  accessToken: string;
  expiresAtMs: number;
  userId: number;
  username: string;
  nickname: string | null;
  tenantCode: string;
}

interface AuthStore {
  session: AuthSession | null;
  remember: boolean;
  setSession: (session: AuthSession, remember: boolean) => void;
  /** 滑动续约（login-renew）：仅替换令牌与过期时间，主体与租户沿用。 */
  renewed: (accessToken: string, expiresAtIso: string) => void;
  clear: () => void;
}

const KEY = 'acme.auth.session.v1';
const REMEMBER_KEY = 'acme.auth.remember.v1';

/** 会话存储选择：记住我 → localStorage（7 天口径由续约维持）；否则 sessionStorage（标签页级）。 */
export function storageFor(remember: boolean): Storage {
  return remember ? window.localStorage : window.sessionStorage;
}

function load(): { session: AuthSession | null; remember: boolean } {
  try {
    const remember = window.localStorage.getItem(REMEMBER_KEY) === '1';
    const raw = storageFor(remember).getItem(KEY);
    if (!raw) {
      return { session: null, remember: false };
    }
    const session = JSON.parse(raw) as AuthSession;
    if (!session?.accessToken) {
      return { session: null, remember: false };
    }
    return { session, remember };
  } catch {
    // 持久化数据损坏按未登录处理（不阻塞应用）
    return { session: null, remember: false };
  }
}

function persist(session: AuthSession, remember: boolean): void {
  try {
    storageFor(remember).setItem(KEY, JSON.stringify(session));
    if (remember) {
      window.localStorage.setItem(REMEMBER_KEY, '1');
      window.sessionStorage.removeItem(KEY);
    } else {
      window.localStorage.removeItem(KEY);
      window.localStorage.removeItem(REMEMBER_KEY);
    }
  } catch {
    // 存储不可用（隐私模式等）：仅保留内存态
  }
}

export const useAuthStore = create<AuthStore>()((set, get) => ({
  ...load(),
  setSession: (session, remember) => {
    persist(session, remember);
    set({ session, remember });
  },
  renewed: (accessToken, expiresAtIso) => {
    const current = get().session;
    if (!current) {
      return;
    }
    const session: AuthSession = { ...current, accessToken, expiresAtMs: Date.parse(expiresAtIso) };
    persist(session, get().remember);
    set({ session });
  },
  clear: () => {
    try {
      window.localStorage.removeItem(KEY);
      window.localStorage.removeItem(REMEMBER_KEY);
      window.sessionStorage.removeItem(KEY);
    } catch {
      // 存储不可用时忽略
    }
    set({ session: null, remember: false });
  },
}));

/**
 * 会话是否已过期（或将在 marginMs 内过期）——续约调度与登录门禁共用判定。
 * marginMs=0 即「已过期」判定；无会话一律视为过期。
 */
export function isExpiringSoon(session: AuthSession | null, marginMs = 120_000, now = Date.now()): boolean {
  return !session || session.expiresAtMs - now <= marginMs;
}
