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

export const useAuthStore = create<AuthStore>()((set, get) => ({
  session: null,
  hydrated: false,
  hydrate: async () => {
    const raw = await secureGet(KEY);
    let session: AuthSession | null = null;
    if (raw) {
      try {
        const parsed = JSON.parse(raw) as AuthSession;
        if (parsed?.accessToken && parsed?.refreshToken) {
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
