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

/** apiFetch 缺省超时毫秒数（REQ-20261114-120103 裁决②：15s，可按调用经 options.timeoutMs 覆盖）。 */
export const DEFAULT_API_TIMEOUT_MS = 15_000;

/** apiFetch 请求 init（REQ-20261114-120103 扩展：signal=标准 RequestInit 面；timeout=毫秒覆盖键）。 */
export type ApiFetchInit = RequestInit & {
  /** 单次尝试超时毫秒数（缺省 15s；0 = 关闭超时）；亦可经 options.timeout 声明（options 优先）。 */
  timeout?: number;
};

/** apiFetch 请求选项（REQ-20261114-120103 扩展 signal/timeout；既有字段与缺省行为不变——签名向后兼容）。 */
export interface ApiFetchOptions {
  auth?: boolean;
  retried?: boolean;
  emptyAsSuccess?: boolean;
  /** 调用方取消信号：透传至底层 fetch（组件卸载/路由切换取消在途请求，AC-4）。 */
  signal?: AbortSignal;
  /** 单次尝试超时毫秒数（缺省 15s；0 = 关闭超时）。超时统一抛 ApiError(0, 'REQUEST_TIMEOUT')。 */
  timeout?: number;
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

/**
 * 相对路径 → 同源绝对 URL：浏览器语义不变（同源相对请求等价绝对 URL），并使
 * node 测试环境（undici fetch / msw node 拦截器仅接受绝对 URL）与浏览器一致（AC-7 MSW 驱动）。
 */
function resolveRequestUrl(path: string): string {
  if (typeof window === 'undefined' || !window.location?.origin) {
    return path;
  }
  try {
    return new URL(path, window.location.origin).href;
  } catch {
    return path;
  }
}

/**
 * 单次带护栏请求：超时与调用方取消都直接收敛为 rejection（不依赖 fetch 对 signal 的
 * 响应——真实 fetch 会拒绝，替身/异常传输不受控时护栏仍兜底），首次收敛即锁定并清理护栏。
 * 超时 → ApiError(0,'REQUEST_TIMEOUT')；调用方取消 → abort reason（或 AbortError）。
 */
function guardedFetch(path: string, init: RequestInit, headers: Headers, timeoutMs: number | undefined): Promise<Response> {
  const external = init.signal ?? null;
  const controller = new AbortController();
  const effective = timeoutMs === 0 ? null : (timeoutMs ?? DEFAULT_API_TIMEOUT_MS);
  let timer: ReturnType<typeof setTimeout> | null = null;
  let settled = false;

  return new Promise<Response>((resolve, reject) => {
    const dispose = () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
      external?.removeEventListener('abort', onExternalAbort);
    };
    const settle = (fn: (value: Response) => void, value: Response) => {
      if (settled) return;
      settled = true;
      dispose();
      fn(value);
    };
    const rejectWith = (err: unknown) => {
      if (settled) return;
      settled = true;
      dispose();
      reject(err);
    };
    const onExternalAbort = () => {
      controller.abort(external?.reason);
      const reason = external?.reason;
      if (reason instanceof Error) {
        rejectWith(reason);
        return;
      }
      // jsdom/部分宿主 DOMException 非 Error 子类：统一收敛为 Error 面（name 保持 AbortError 语义）
      const abortError = new Error('The operation was aborted');
      abortError.name = 'AbortError';
      rejectWith(abortError);
    };

    if (effective !== null) {
      timer = setTimeout(() => {
        controller.abort();
        const seconds = Math.round((effective ?? DEFAULT_API_TIMEOUT_MS) / 1000);
        rejectWith(new ApiError(0, 'REQUEST_TIMEOUT', `请求超时（${seconds}s），请稍后重试`));
      }, effective);
    }
    if (external) {
      if (external.aborted) {
        controller.abort(external.reason);
      } else {
        external.addEventListener('abort', onExternalAbort);
      }
    }
    // 跨 realm 宿主兜底（vitest jsdom + undici fetch / msw node 拦截）：signal 品牌校验失败
    // → 以无 signal 形态重发；护栏的 rejection 收敛不依赖 fetch 响应 abort，超时/取消语义不变。
    const fetchWithSignal = (): Promise<Response> =>
      fetch(resolveRequestUrl(path), { ...init, headers, signal: controller.signal });
    const fetchWithoutSignal = (): Promise<Response> => fetch(resolveRequestUrl(path), { ...init, headers });
    const isSignalRejection = (err: unknown): boolean =>
      err instanceof TypeError && /AbortSignal/i.test(err.message);
    let fetchPromise: Promise<Response>;
    try {
      fetchPromise = fetchWithSignal().catch((err: unknown) => {
        if (isSignalRejection(err)) {
          return fetchWithoutSignal();
        }
        throw err;
      });
    } catch (err: unknown) {
      if (!isSignalRejection(err)) {
        throw err;
      }
      fetchPromise = fetchWithoutSignal();
    }
    void fetchPromise.then(
      (res) => settle(resolve, res),
      (err: unknown) => rejectWith(err),
    );
  });
}

/** 请求主路径（含 401 单飞续约 + 原样重试；续约失败清会话回登录页）。 */
async function request<T>(
  path: string,
  init: RequestInit,
  options: { auth?: boolean; retried?: boolean; emptyAsSuccess?: boolean; timeout?: number },
): Promise<T> {
  const { auth = true, retried = false, emptyAsSuccess = false, timeout } = options;
  const headers = new Headers(init.headers);
  const session = useAuthStore.getState().session;
  if (auth && session) {
    headers.set('Authorization', `Bearer ${session.accessToken}`);
  }
  const res = await guardedFetch(path, init, headers, timeout);
  if (res.status === 401 && auth && !retried) {
    // 滑动续约一次后原样重试；续约失败即会话终止 → 回登录页
    const renewed = await renewOnce();
    if (renewed) {
      return request<T>(path, init, { auth, retried: true, emptyAsSuccess, timeout });
    }
    useAuthStore.getState().clear();
    if (window.location.hash !== '#/login') {
      window.location.hash = '#/login';
    }
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

/** GET 去重：同 URL（含查询参数）+ 同选项的并发在途请求合并，响应共享（AC-6）。 */
const inflightGets = new Map<string, Promise<unknown>>();

export async function apiFetch<T>(
  path: string,
  init: ApiFetchInit = {},
  options: ApiFetchOptions = {},
): Promise<T> {
  const { auth = true, retried = false, emptyAsSuccess = false, signal, timeout: optionsTimeout } = options;
  // 超时覆盖双写面：init.timeout（RequestInit 扩展键，就近语义）与 options.timeout（显式选项）；
  // 同时给出时 options 优先。缺省 15s（DEFAULT_API_TIMEOUT_MS），0 = 关闭。
  const initTimeout = typeof init.timeout === 'number' ? init.timeout : undefined;
  const timeout = optionsTimeout ?? initTimeout;
  const method = (init.method ?? 'GET').toUpperCase();
  // GET 去重边界：仅并发在途的 GET 合并（POST/PUT/DELETE 不去重）；携带调用方 signal 或续约重试
  // 的请求不参与合并（signal 合并会耦合各方取消语义；重试并入原在途 promise 会自等死锁）。
  // signal 双写面（options.signal / init.signal）：漏判任一面会使带 init.signal 的请求并入合并，
  // 调用方 abort 后并发共享者同收取消拒绝（StrictMode 双挂载首请求必报错）。
  const callerSignal = signal ?? init.signal ?? null;
  const dedupKey = method === 'GET' && !retried && !callerSignal
    ? `GET ${path}|auth=${String(auth)}|empty=${String(emptyAsSuccess)}|timeout=${timeout ?? DEFAULT_API_TIMEOUT_MS}`
    : null;
  if (dedupKey) {
    const existing = inflightGets.get(dedupKey);
    if (existing) {
      return existing as Promise<T>;
    }
  }
  // options.signal 为新增 API 面（优先于 init.signal 显式声明）；经护栏作用域透传至底层 fetch。
  const requestInit: RequestInit = signal ? { ...init, signal } : init;
  const promise = request<T>(path, requestInit, { auth, retried, emptyAsSuccess, timeout });
  if (dedupKey) {
    const shared = promise.finally(() => {
      if (inflightGets.get(dedupKey) === shared) {
        inflightGets.delete(dedupKey);
      }
    });
    inflightGets.set(dedupKey, shared);
    return shared;
  }
  return promise;
}

/**
 * 原始响应获取（文件流下载/导出等二进制端点）：与 apiFetch 同源携带鉴权并复用
 * 单飞续约（401 续约一次后原样重试；续约失败清会话回登录页），不做信封解析——
 * 响应体形态（blob/流）与 Content-Disposition（下载原名）由调用方自行处理。
 */
export async function apiFetchRaw(
  path: string,
  init: RequestInit = {},
  options: { auth?: boolean; retried?: boolean } = {},
): Promise<Response> {
  const { auth = true, retried = false } = options;
  const headers = new Headers(init.headers);
  const session = useAuthStore.getState().session;
  if (auth && session) {
    headers.set('Authorization', `Bearer ${session.accessToken}`);
  }
  const res = await fetch(resolveRequestUrl(path), { ...init, headers });
  if (res.status === 401 && auth && !retried) {
    const renewed = await renewOnce();
    if (renewed) {
      return apiFetchRaw(path, init, { auth, retried: true });
    }
    useAuthStore.getState().clear();
    if (window.location.hash !== '#/login') {
      window.location.hash = '#/login';
    }
    throw new ApiError(401, 'AUTH_REQUIRED', '登录状态已失效，请重新登录');
  }
  return res;
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
