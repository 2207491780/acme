import { create } from 'zustand';
import { notifyApi } from '../api/notify';
import type { NotifyMessageNewFrame } from '../api/notify';

/**
 * 通知未读数 store（REQ-20261114-120102，契约裁决⑪表面钉死）：
 * - 状态：unreadCount（未读站内信数，Header 铃铛徽标数据源）；
 * - 动作面：refreshUnread()（挂载兜底拉取）/ applyMessageNew(frame)（WS message:new 实时 +1，
 *   帧按契约结构原样入参）/ markRead(id)（单条已读，本地递减）/ markAllRead()（全部已读，本地清零）。
 *
 * 未读数收敛口径（裁决⑪）：已读动作后「本地递减/清零」与「重拉 unread-count」两策略均可，
 * 此处取本地收敛（省一次请求；WS 实时链路兜底由 refreshUnread 承担）。
 */
interface NotifyStore {
  unreadCount: number;
  /** 兜底拉取未读数（GET unread-count，data=裸数字）；失败保留现值不覆盖。 */
  refreshUnread: () => Promise<void>;
  /** WS message:new 帧到达：未读数实时 +1。 */
  applyMessageNew: (frame: NotifyMessageNewFrame) => void;
  /** 单条已读：PUT read 成功后本地递减（下限 0）；失败抛给调用方处理。 */
  markRead: (messageId: string) => Promise<void>;
  /** 全部已读：PUT read-all 成功后本地清零；失败抛给调用方处理。 */
  markAllRead: () => Promise<void>;
}

export const useNotifyStore = create<NotifyStore>()((set, get) => ({
  unreadCount: 0,
  refreshUnread: async () => {
    try {
      const data = await notifyApi.messageUnreadCount();
      // 运行时校验（契约裁决⑩ data=裸数字）：异常载荷不覆盖现值，防渲染崩溃
      if (typeof data !== 'number' || !Number.isFinite(data)) {
        return;
      }
      set({ unreadCount: data });
    } catch {
      // 兜底拉取失败不打断页面：保留现值（WS 实时链路/下次挂载仍可收敛）
    }
  },
  applyMessageNew: (_frame) => {
    // 帧（messageId/title）当前仅承载未读 +1 语义；后续按 messageId 去重等在此扩展
    set({ unreadCount: get().unreadCount + 1 });
  },
  markRead: async (messageId) => {
    await notifyApi.messageRead(messageId);
    set({ unreadCount: Math.max(0, get().unreadCount - 1) });
  },
  markAllRead: async () => {
    await notifyApi.messageReadAll();
    set({ unreadCount: 0 });
  },
}));
