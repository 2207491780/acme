import { useCallback, useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { useTranslation } from 'react-i18next';
import { ApiError, authApi, type QrTicketStatus, type TokenIssued } from '@acme/core';

/** 扫码登录轮询间隔毫秒（契约 REQ-20261119-040838 确认记录⑥：每 1.5s GET 一次）。 */
export const QR_POLL_INTERVAL_MS = 1500;

/** 二维码渲染边长像素（契约 AC-6：200px）。 */
export const QR_SIZE_PX = 200;

export interface UseQrLoginOptions {
  /** 轮询到 confirmed 且 token 非空时回调一次（LoginPage 完成 setSession + 跳转主界面）。 */
  onConfirmed: (issued: TokenIssued) => void;
}

export interface QrLoginState {
  /** 二维码 dataURL（票据创建成功后非空；本地 qrcode 库生成，同源无外链）。 */
  dataUrl: string | null;
  /** 票据当前状态；创建在途/失败时为 null。 */
  status: QrTicketStatus | null;
  /** 票据创建失败提示（轮询瞬时失败不置此态，保持既有状态继续轮询）。 */
  createError: string | null;
  /** 是否正在（重）创建票据。 */
  creating: boolean;
  /** 重新创建票据并重启轮询（旧票据会话立即作废）。 */
  refresh: () => void;
}

/**
 * 扫码登录票据生命周期 hook（REQ-20261119-040838 AC-6）：
 * - 挂载即创建票据（POST /admin/v1/auth/qr-tickets，公开）→ qrcode.toDataURL(qrPayload) 渲染；
 * - 创建成功后立即首查，随后每 QR_POLL_INTERVAL_MS 轮询 GET status（页面卸载/终态/手动刷新停止）；
 * - confirmed 且 token 非空 → 停止轮询并回调 onConfirmed（签发确认者 B 端令牌）；
 * - cancelled/expired 终态 → 停止轮询（面板提示刷新重建票据）；
 * - 竞态护栏：sessionRef 会话号——手动刷新/卸载后旧票据的响应与定时器全部作废。
 */
export function useQrLogin({ onConfirmed }: UseQrLoginOptions): QrLoginState {
  const { t } = useTranslation();
  // t/onConfirmed 均经 ref 消费：effect 与回调闭包不依赖其身份，避免 locale/渲染变化重启轮询
  const tRef = useRef(t);
  tRef.current = t;
  const onConfirmedRef = useRef(onConfirmed);
  onConfirmedRef.current = onConfirmed;

  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [status, setStatus] = useState<QrTicketStatus | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(true);

  // 会话号：每次 start/卸载递增，旧会话的响应与 interval 一律作废
  const sessionRef = useRef(0);
  const timerRef = useRef<number | null>(null);

  const stopPolling = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const poll = useCallback(
    async (sessionId: number, ticketId: string): Promise<void> => {
      try {
        const view = await authApi.qrPoll(ticketId);
        if (sessionRef.current !== sessionId) {
          return;
        }
        setStatus(view.status);
        if (view.status === 'confirmed' && view.token) {
          stopPolling();
          onConfirmedRef.current(view.token);
          return;
        }
        // 终态（cancelled/expired）即停止；confirmed 上分支已含停止
        if (view.status === 'cancelled' || view.status === 'expired') {
          stopPolling();
        }
      } catch {
        // 瞬时网络失败：保持既有状态继续轮询（下一 tick 自愈；过期等业务失败以 status 面呈现）
      }
    },
    [stopPolling],
  );

  const start = useCallback(() => {
    const sessionId = ++sessionRef.current;
    stopPolling();
    setCreating(true);
    setCreateError(null);
    setStatus(null);
    setDataUrl(null);
    authApi.qrCreate()
      .then(async (view) => {
        if (sessionRef.current !== sessionId) {
          return;
        }
        const url = await QRCode.toDataURL(view.qrPayload, { width: QR_SIZE_PX, margin: 1 });
        if (sessionRef.current !== sessionId) {
          return;
        }
        setDataUrl(url);
        setStatus('created');
        setCreating(false);
        // 首查立即执行（创建成功即应反映服务端状态），后续按契约间隔轮询
        void poll(sessionId, view.ticketId);
        timerRef.current = window.setInterval(
          () => void poll(sessionId, view.ticketId),
          QR_POLL_INTERVAL_MS,
        );
      })
      .catch((ex: unknown) => {
        if (sessionRef.current !== sessionId) {
          return;
        }
        setCreating(false);
        setCreateError(ex instanceof ApiError ? ex.message : tRef.current('login.qr.createFailed'));
      });
  }, [poll, stopPolling]);

  // 挂载创建 + 卸载停止（AC-6：页面卸载停止轮询）
  const startRef = useRef(start);
  startRef.current = start;
  useEffect(() => {
    startRef.current();
    return () => {
      sessionRef.current += 1;
      stopPolling();
    };
  }, [stopPolling]);

  const refresh = useCallback(() => start(), [start]);

  return { dataUrl, status, createError, creating, refresh };
}
