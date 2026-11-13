import { API_BASE } from './config';
import { useAuthStore } from '../store/authStore';

/** 后端统一出参信封（FR-013；与 Web 端 client.ts 同型口径）。 */
export interface ApiEnvelope<T> {
  success: boolean;
  code: string;
  message: string | null;
  data: T;
}

/** 接口业务错误（携带后端错误码与 HTTP 状态）。 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 单飞刷新：并发 401 仅触发一次刷新轮换（消费端一次性轮换口径）。 */
let refreshing: Promise<boolean> | null = null;

function refreshOnce(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const session = useAuthStore.getState().session;
      if (!session) {
        return false;
      }
      // 刷新令牌本地已过期时不必发起注定失败的轮换（review 修复：此前 refreshExpiresAtMs 从不检查）
      if (!Number.isFinite(session.refreshExpiresAtMs) || session.refreshExpiresAtMs <= Date.now()) {
        return false;
      }
      const res = await fetch(`${API_BASE}/app/v1/auth/tokens/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
      });
      if (!res.ok) {
        return false;
      }
      const envelope = (await res.json()) as ApiEnvelope<{
        accessToken: string;
        refreshToken: string;
        expiresAt: string;
        refreshExpiresAt: string;
      }>;
      if (!envelope.success || !envelope.data?.accessToken) {
        return false;
      }
      useAuthStore.getState().renewed(envelope.data);
      return true;
    } catch {
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  options: { auth?: boolean; retried?: boolean } = {},
): Promise<T> {
  const { auth = true, retried = false } = options;
  const headers = new Headers(init.headers);
  const session = useAuthStore.getState().session;
  if (auth && session) {
    headers.set('Authorization', `Bearer ${session.accessToken}`);
  }
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, 'NETWORK_UNREACHABLE', '网络不可达，请检查网络连接');
  }
  if (res.status === 401 && auth && !retried) {
    const refreshed = await refreshOnce();
    if (refreshed) {
      return apiFetch<T>(path, init, { auth, retried: true });
    }
    useAuthStore.getState().clear();
    throw new ApiError(401, 'AUTH_REQUIRED', '登录状态已失效，请重新登录');
  }
  const envelope = (await res.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!envelope) {
    throw new ApiError(res.status, 'EMPTY_BODY', `接口无响应体（HTTP ${res.status}）`);
  }
  if (!envelope.success) {
    throw new ApiError(res.status, envelope.code, envelope.message ?? '请求失败');
  }
  return envelope.data;
}

/**
 * 续约守护循环（login-app）：每 30s 巡检，access 将在 2 分钟内过期即刷新轮换；
 * 刷新失败且已实际过期 → 清会话回登录屏。返回停止函数。
 */
export function startAuthRenewal(): () => void {
  const timer = setInterval(() => {
    const session = useAuthStore.getState().session;
    if (!session || !isExpiringSoon(session)) {
      return;
    }
    void refreshOnce().then((ok) => {
      if (!ok && isExpiringSoon(useAuthStore.getState().session, 0)) {
        useAuthStore.getState().clear();
      }
    });
  }, 30_000);
  return () => clearInterval(timer);
}

/** 会话是否已过期（或将在 marginMs 内过期）；无会话一律视为过期。 */
export function isExpiringSoon(
  session: { accessExpiresAtMs: number } | null,
  marginMs = 120_000,
  now = Date.now(),
): boolean {
  return !session || session.accessExpiresAtMs - now <= marginMs;
}
