import { create } from 'zustand';
import { secureDelete, secureGet, secureSet } from '../api/secureStore';

/** B 端（员工）会话（REQ-20261119-040838）：C 端 AuthSession 的 access-only 变体——
 *  无 refreshToken（B 端 30 分钟短会话，滑动续约由 Web/桌面端承担；APP 扫码确认场景
 *  短会话即可）；userId 为字符串化雪花 ID，与 wire 口径一致。独立键独立存储，与
 *  C 端 acme.auth.session.v1 互不干扰。 */
export interface BSession {
  accessToken: string;
  accessExpiresAtMs: number;
  userId: string;
  username: string;
  nickname: string | null;
  tenantCode: string;
}

interface BSessionStore {
  session: BSession | null;
  hydrated: boolean;
  /** 应用启动时从安全存储恢复 B 会话（已本地过期即丢弃：无刷新令牌不可续）。 */
  hydrate: () => Promise<void>;
  setSession: (session: BSession) => void;
  clear: () => void;
}

const KEY = 'acme.auth.bsession.v1';

/** 会话恢复运行时校验（对齐 authStore 口径）：持久化数据不得用类型断言信任，
 *  任一字段缺失/类型不符即拒绝恢复。access-only：允许无 refreshToken。 */
function isValidBSession(value: unknown): value is BSession {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const s = value as Partial<BSession>;
  return (
    typeof s.accessToken === 'string' &&
    s.accessToken.length > 0 &&
    typeof s.accessExpiresAtMs === 'number' &&
    Number.isFinite(s.accessExpiresAtMs) &&
    s.accessExpiresAtMs > 0 &&
    typeof s.userId === 'string' &&
    s.userId.length > 0 &&
    typeof s.username === 'string' &&
    s.username.length > 0 &&
    (s.nickname === null || typeof s.nickname === 'string') &&
    typeof s.tenantCode === 'string' &&
    s.tenantCode.length > 0
  );
}

export const useBSessionStore = create<BSessionStore>()((set) => ({
  session: null,
  hydrated: false,
  hydrate: async () => {
    const raw = await secureGet(KEY);
    let session: BSession | null = null;
    if (raw) {
      try {
        const parsed: unknown = JSON.parse(raw);
        if (isValidBSession(parsed)) {
          // 本地已过期 → 丢弃（无 refreshToken，过期会话注定不可用）
          if (parsed.accessExpiresAtMs > Date.now()) {
            session = parsed;
          } else {
            void secureDelete(KEY);
          }
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
  clear: () => {
    void secureDelete(KEY);
    set({ session: null });
  },
}));

/** B 端令牌请求头（/admin/v1 认证端点专用，如 qrInfo/qrConfirm/qrCancel）：
 *  mobile apiFetch 的 auth:true 只认 C 端 authStore 会话，B 端端点经显式头携带
 *  B 会话令牌（无 B 会话时不带头，由服务端 401 语义兜底）。 */
export function bAuthHeaders(): Record<string, string> {
  const session = useBSessionStore.getState().session;
  return session ? { Authorization: `Bearer ${session.accessToken}` } : {};
}
