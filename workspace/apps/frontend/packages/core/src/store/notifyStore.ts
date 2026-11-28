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
  /** 已见 messageId 集（P3-03 去重：WS 重连重放/重复帧不虚增徽标；store 生命周期内存续存）。 */
  seenMessageIds: Set<string>;
  /** 兜底拉取未读数（GET unread-count，data=裸数字）；失败保留现值不覆盖。 */
  refreshUnread: () => Promise<void>;
  /** WS message:new 帧到达：按 messageId 去重后未读数 +1（重复帧忽略）。 */
  applyMessageNew: (frame: NotifyMessageNewFrame) => void;
  /** 单条已读：PUT read 成功后本地递减（下限 0）；失败抛给调用方处理。 */
  markRead: (messageId: string) => Promise<void>;
  /** 全部已读：PUT read-all 成功后本地清零；失败抛给调用方处理。 */
  markAllRead: () => Promise<void>;
}

export const useNotifyStore = create<NotifyStore>()((set, get) => ({
  unreadCount: 0,
  seenMessageIds: new Set<string>(),
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
  applyMessageNew: (frame) => {
    // P3-03：按 messageId 去重——WS 重连重放/服务端重复帧只计一次，防徽标虚增；
    // 空 messageId（契约不允许，防御）不计数不登记
    if (!frame.messageId || get().seenMessageIds.has(frame.messageId)) {
      return;
    }
    const seenMessageIds = new Set(get().seenMessageIds);
    seenMessageIds.add(frame.messageId);
    set({ seenMessageIds, unreadCount: get().unreadCount + 1 });
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
