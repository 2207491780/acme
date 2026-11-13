import { create } from 'zustand';
import { secureDelete, secureGet, secureSet } from '../api/secureStore';

/** C 端登录会话（login-app）：双令牌 + 主体；过期时间为本地 epoch 毫秒。 */
export interface AuthSession {
  accessToken: string;
  refreshToken: string;
  accessExpiresAtMs: number;
  refreshExpiresAtMs: number;
  userId: number;
  phone: string;
}

interface AuthStore {
  session: AuthSession | null;
  hydrated: boolean;
  /** 应用启动时从安全存储恢复会话（App 门禁在 hydrate 完成前不渲染主界面）。 */
  hydrate: () => Promise<void>;
  setSession: (session: AuthSession) => void;
  /** 刷新轮换后仅替换令牌对与过期时间（主体沿用）。 */
  renewed: (issued: {
    accessToken: string;
    refreshToken: string;
    expiresAt: string;
    refreshExpiresAt: string;
  }) => void;
  clear: () => void;
}

const KEY = 'acme.auth.session.v1';

/** 会话恢复运行时校验（review 修复）：持久化数据不得用类型断言信任，
 * 任一字段缺失/类型不符即拒绝恢复，避免损坏数据产生 NaN 过期时间与注定失败的轮换调用。 */
function isValidSession(value: unknown): value is AuthSession {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const s = value as Partial<AuthSession>;
  return (
    typeof s.accessToken === 'string' &&
    s.accessToken.length > 0 &&
    typeof s.refreshToken === 'string' &&
    s.refreshToken.length > 0 &&
    typeof s.accessExpiresAtMs === 'number' &&
    Number.isFinite(s.accessExpiresAtMs) &&
    s.accessExpiresAtMs > 0 &&
    typeof s.refreshExpiresAtMs === 'number' &&
    Number.isFinite(s.refreshExpiresAtMs) &&
    s.refreshExpiresAtMs > 0 &&
    typeof s.userId === 'number' &&
    Number.isFinite(s.userId) &&
    typeof s.phone === 'string' &&
    s.phone.length > 0
  );
}

export const useAuthStore = create<AuthStore>()((set, get) => ({
  session: null,
  hydrated: false,
  hydrate: async () => {
    const raw = await secureGet(KEY);
    let session: AuthSession | null = null;
    if (raw) {
      try {
        const parsed: unknown = JSON.parse(raw);
        if (isValidSession(parsed)) {
          session = parsed;
        }
      } catch {
        // 持久化数据损坏按未登录处理
      }
    }
    set({ session, hydrated: true });
  },
  setSession: (session) => {
    void secureSet(KEY, JSON.stringify(session));
    set({ session });
  },
  renewed: (issued) => {
    const current = get().session;
    if (!current) {
      return;
    }
    const session: AuthSession = {
      ...current,
      accessToken: issued.accessToken,
      refreshToken: issued.refreshToken,
      accessExpiresAtMs: Date.parse(issued.expiresAt),
      refreshExpiresAtMs: Date.parse(issued.refreshExpiresAt),
    };
    void secureSet(KEY, JSON.stringify(session));
    set({ session });
  },
  clear: () => {
    void secureDelete(KEY);
    set({ session: null });
  },
}));
