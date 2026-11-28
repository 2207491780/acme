import { DEFAULT_API_TIMEOUT_MS } from '@acme/core';
import { API_BASE } from './config';
import { useAuthStore } from '../store/authStore';

// 后端统一出参信封（FR-013）：跨端声明单源化——从 @acme/core 重导出（P3-01，与 core
// client.ts 同型口径；结构等价哈希核验后收敛，消费面引用不变）。
import type { ApiEnvelope } from '@acme/core';
export type { ApiEnvelope };

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
        accessExpiresAt: string;
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

/** 单次请求超时护栏（P3-02，对齐 core guardedFetch 口径：缺省 15s 可覆盖、0=关闭；
 *  超时抛 ApiError(0,'REQUEST_TIMEOUT')，其余异常原样上抛交调用面归因网络不可达）。
 *  mobile 无外部 signal 面（组件取消场景未建模），护栏 abort 即唯一 abort 源。 */
async function guardedFetch(url: string, init: RequestInit, timeoutMs: number | undefined): Promise<Response> {
  const effective = timeoutMs === 0 ? null : (timeoutMs ?? DEFAULT_API_TIMEOUT_MS);
  if (effective === null) {
    return fetch(url, init);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), effective);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') {
      const seconds = Math.round(effective / 1000);
      throw new ApiError(0, 'REQUEST_TIMEOUT', `请求超时（${seconds}s），请稍后重试`);
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  options: { auth?: boolean; retried?: boolean; emptyAsSuccess?: boolean; timeout?: number } = {},
): Promise<T> {
  const { auth = true, retried = false, emptyAsSuccess = false, timeout } = options;
  const headers = new Headers(init.headers);
  const session = useAuthStore.getState().session;
  if (auth && session) {
    headers.set('Authorization', `Bearer ${session.accessToken}`);
  }
  let res: Response;
  try {
    res = await guardedFetch(`${API_BASE}${path}`, { ...init, headers }, timeout);
  } catch (err) {
    if (err instanceof ApiError) {
      throw err;
    }
    throw new ApiError(0, 'NETWORK_UNREACHABLE', '网络不可达，请检查网络连接');
  }
  if (res.status === 401 && auth && !retried) {
    const refreshed = await refreshOnce();
    if (refreshed) {
      return apiFetch<T>(path, init, { auth, retried: true, emptyAsSuccess });
    }
    useAuthStore.getState().clear();
    throw new ApiError(401, 'AUTH_REQUIRED', '登录状态已失效，请重新登录');
  }
  const envelope = (await res.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!envelope) {
    // void 端点按 Modules.md 3.1.1 豁免不出信封（2xx 空体=成功）；未声明豁免的空体仍按异常处理
    if (emptyAsSuccess && res.ok) {
      return undefined as T;
    }
    throw new ApiError(res.status, 'EMPTY_BODY', `接口无响应体（HTTP ${res.status}）`);
  }
  if (!envelope.success) {
    throw new ApiError(res.status, envelope.code, envelope.message ?? '请求失败');
  }
  return envelope.data;
}

/** JSON 体请求快捷面（apiJson）：Content-Type + JSON.stringify 样板收敛，行为与手写 init 逐字等价；
 *  extraHeaders 供 B 端认证端点合并 bAuthHeaders（展开序在 Content-Type 之后，可覆盖语义不变）；
 *  timeout 透传 apiFetch 超时护栏（P3-02）。 */
export function apiJson<T>(
  path: string,
  method: 'POST' | 'PUT' | 'PATCH',
  payload: unknown,
  options: {
    auth?: boolean;
    retried?: boolean;
    emptyAsSuccess?: boolean;
    extraHeaders?: Record<string, string>;
    timeout?: number;
  } = {},
): Promise<T> {
  const { auth = true, retried = false, emptyAsSuccess = false, extraHeaders, timeout } = options;
  return apiFetch<T>(
    path,
    {
      method,
      headers: { 'Content-Type': 'application/json', ...extraHeaders },
      body: JSON.stringify(payload),
    },
    { auth, retried, emptyAsSuccess, timeout },
  );
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
