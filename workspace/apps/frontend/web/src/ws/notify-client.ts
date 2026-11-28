import { useAuthStore, useNotifyStore } from '@acme/core';
import type { NotifyMessageNewFrame } from '@acme/core';

/**
 * 站内信 WS 客户端（REQ-20261114-120102，AC-13；契约裁决⑪表面钉死）：
 * - 命名导出工厂 createNotifyClient()，面 = { connect, close, on }；
 * - 读全局 WebSocket（浏览器原生/测试可注入），token 取自 useAuthStore 会话，
 *   握手经 `?token=` query（浏览器 WS 无法自定义头，契约裁决⑦）；
 * - 异常断线指数退避重连（相邻间隔比值 1.5–2.6，调度经全局 setTimeout）；
 *   主动 close() 视为正常关闭、不重连；重连建连成功后事件分发链路存活。
 * - P2-13 断线对账：建连/重连成功与 visibility 恢复时 REST 拉取 unread（WS pub/sub
 *   不持久化，后台页签丢帧在切回/重连时收敛）。
 *
 * 心跳为服务端语义（30s，确认记录④⑧）：协议层 ping/pong 由浏览器自动应答，
 * 客户端不额外发应用层心跳帧。
 */

export type NotifyFrameHandler = (frame: NotifyMessageNewFrame) => void;

/** 可注入性：客户端类型只承诺 { connect, close, on } 三方法。 */
export interface NotifyClient {
  connect: () => void;
  close: () => void;
  on: (type: string, handler: NotifyFrameHandler) => void;
}

/** 重连退避基值/倍率/上限（基值与倍率使相邻间隔比值恒为 2，落在契约容差 1.5–2.6 内）。 */
const RECONNECT_BASE_MS = 1000;
const RECONNECT_FACTOR = 2;
const RECONNECT_MAX_MS = 30_000;
/** 连接稳定阈值：保持打开超过该时长后断线，视为新故障重置退避档位（长稳连接不惩罚）。 */
const RECONNECT_STABLE_MS = 60_000;

/** 同源 WS 端点：/ws/system/notify（网关路由 `/ws/system/**` → platform-system）。 */
const NOTIFY_WS_PATH = '/ws/system/notify';

export function createNotifyClient(): NotifyClient {
  const handlers = new Map<string, Set<NotifyFrameHandler>>();
  let socket: WebSocket | null = null;
  let deliberatelyClosed = false;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** 连续异常断线次数（决定退避档位）。 */
  let attempts = 0;
  /** 当前连接打开时刻（ms epoch；null=未打开）。 */
  let openedAt: number | null = null;

  const clearReconnectTimer = (): void => {
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  };

  const scheduleReconnect = (): void => {
    if (deliberatelyClosed || reconnectTimer !== null) {
      return;
    }
    const delay = Math.min(RECONNECT_BASE_MS * RECONNECT_FACTOR ** attempts, RECONNECT_MAX_MS);
    attempts += 1;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, delay);
  };

  const buildUrl = (): string | null => {
    const token = useAuthStore.getState().session?.accessToken;
    if (!token) {
      return null;
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}${NOTIFY_WS_PATH}?token=${encodeURIComponent(token)}`;
  };

  const connect = (): void => {
    if (deliberatelyClosed) {
      return;
    }
    // 已有连接在建/打开中不重复建连（重连调度统一经 scheduleReconnect 入口）
    if (socket && socket.readyState <= 1) {
      return;
    }
    const url = buildUrl();
    if (!url) {
      // P2-14：会话未就绪不再静默死面（建连时会话未就绪曾→永久不连）——
      // 排退避重连（有界 30s 上限），登录/会话恢复由 isAuthed 翻转监听即时建连
      scheduleReconnect();
      return;
    }
    // 运行时取全局 WebSocket（vi.stubGlobal 可注入替换；模块不缓存构造器引用）
    const next = new WebSocket(url);
    socket = next;
    next.onopen = () => {
      openedAt = Date.now();
      // P2-13：建连/重连成功即 REST 拉取 unread 对账（WS pub/sub 不持久化，丢帧据此收敛）
      void useNotifyStore.getState().refreshUnread();
    };
    next.onmessage = (ev: MessageEvent) => {
      let frame: unknown;
      try {
        frame = JSON.parse(String(ev.data));
      } catch {
        return; // 非 JSON 帧忽略（服务端仅推送 JSON 事件帧）
      }
      if (typeof frame !== 'object' || frame === null) {
        return;
      }
      const type = (frame as { type?: unknown }).type;
      if (typeof type !== 'string') {
        return;
      }
      for (const handler of handlers.get(type) ?? []) {
        handler(frame as NotifyMessageNewFrame);
      }
    };
    next.onclose = () => {
      if (socket !== next) {
        return; // 过期连接（已被新连接替代）的残帧不参与退避
      }
      socket = null;
      if (openedAt !== null && Date.now() - openedAt > RECONNECT_STABLE_MS) {
        attempts = 0; // 长稳连接后断线 → 退避档位重置
      }
      openedAt = null;
      scheduleReconnect();
    };
    next.onerror = () => {
      // 错误细节随后必有 close 事件，退避统一在 onclose 处理
    };
  };

  const close = (): void => {
    deliberatelyClosed = true;
    clearReconnectTimer();
    unsubscribeAuthFlip();
    document.removeEventListener('visibilitychange', onVisibility);
    if (socket) {
      const closing = socket;
      socket = null;
      closing.onclose = null; // 主动关闭不触发退避
      closing.close();
    }
  };

  const on = (type: string, handler: NotifyFrameHandler): void => {
    const set = handlers.get(type) ?? new Set<NotifyFrameHandler>();
    set.add(handler);
    handlers.set(type, set);
  };

  // P2-13：visibility 恢复（页签切回可见）且连接存活时 REST 对账 unread——
  // 后台页签期间的推送丢帧在切回时收敛（close() 注销监听防泄漏）。
  const onVisibility = (): void => {
    if (document.visibilityState === 'visible' && socket !== null && socket.readyState === 1) {
      void useNotifyStore.getState().refreshUnread();
    }
  };
  document.addEventListener('visibilitychange', onVisibility);

  // P2-14：isAuthed 翻转监听——登录/会话恢复（无会话→有会话）即重置退避并立即建连；
  // 主动关闭态不建连（deliberatelyClosed 语义优先）；close() 注销监听防泄漏。
  const unsubscribeAuthFlip = useAuthStore.subscribe((state, prev) => {
    const authed = state.session != null;
    const wasAuthed = prev.session != null;
    if (authed && !wasAuthed && !deliberatelyClosed && (socket === null || socket.readyState > 1)) {
      attempts = 0;
      clearReconnectTimer();
      connect();
    }
  });

  return { connect, close, on };
}
