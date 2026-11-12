import { useAuthStore, isExpiringSoon } from '../store/authStore';

/** 后端统一出参信封（FR-013：Result.ok / Result.fail；成功码 "0"）。 */
export interface ApiEnvelope<T> {
  success: boolean;
  code: string;
  message: string | null;
  data: T;
}

/** 接口业务错误（携带后端错误码与 HTTP 状态，供页面按态渲染）。 */
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

interface RenewedToken {
  accessToken: string;
  expiresAt: string;
}

/** 单飞续约：并发 401 / 到期检查仅触发一次滑动续约（login-renew）。 */
let renewing: Promise<boolean> | null = null;

function renewOnce(): Promise<boolean> {
  renewing ??= (async () => {
    try {
      const session = useAuthStore.getState().session;
      if (!session) {
        return false;
      }
      const res = await fetch('/admin/v1/auth/tokens/refresh', {
        method: 'POST',
        headers: { Authorization: `Bearer ${session.accessToken}` },
      });
      if (!res.ok) {
        return false;
      }
      const envelope = (await res.json()) as ApiEnvelope<RenewedToken>;
      if (!envelope.success || !envelope.data?.accessToken) {
        return false;
      }
      useAuthStore.getState().renewed(envelope.data.accessToken, envelope.data.expiresAt);
      return true;
    } catch {
      return false;
    } finally {
      renewing = null;
    }
  })();
  return renewing;
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
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && auth && !retried) {
    // 滑动续约一次后原样重试；续约失败即会话终止 → 回登录页
    const renewed = await renewOnce();
    if (renewed) {
      return apiFetch<T>(path, init, { auth, retried: true });
    }
    useAuthStore.getState().clear();
    if (window.location.hash !== '#/login') {
      window.location.hash = '#/login';
    }
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
 * 续约守护循环（login-renew）：每 30s 巡检，令牌将在 2 分钟内过期即滑动续约；
 * 续约失败且已实际过期 → 清会话回登录页。返回停止函数（App 卸载时清理）。
 */
export function startAuthRenewal(): () => void {
  const timer = window.setInterval(() => {
    const session = useAuthStore.getState().session;
    if (!session || !isExpiringSoon(session)) {
      return;
    }
    void renewOnce().then((ok) => {
      if (!ok && isExpiringSoon(useAuthStore.getState().session, 0)) {
        useAuthStore.getState().clear();
        window.location.hash = '#/login';
      }
    });
  }, 30_000);
  return () => window.clearInterval(timer);
}
