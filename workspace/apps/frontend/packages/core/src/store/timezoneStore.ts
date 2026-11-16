import { create } from 'zustand';
import { isSupportedTimezone, setTimezone, type TimezonePref } from '../date-format';

/**
 * 展示时区 store（日期国际化·时区维度）：
 * - 持久化（key 版本化 acme.timezone.v1，照 localeStore 模式：运行时校验 + 存储异常降级内存态）；
 * - 缺省「跟随系统」（system 哨兵，生效时区取运行环境本地时区）；
 * - setTimezone 统一完成持久化 + date-format 全局缺省联动 + store 状态（React 重渲染）。
 */

const KEY = 'acme.timezone.v1';

interface TimezoneStore {
  timezone: TimezonePref;
  setTimezone: (timezone: TimezonePref) => void;
}

/** 读取持久化时区：损坏/未知值/存储不可用一律回退缺省 system（不阻塞应用）。 */
export function loadPersistedTimezone(): TimezonePref {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) {
      return 'system';
    }
    const parsed: unknown = JSON.parse(raw);
    // 兼容两种落盘形状：裸字符串 或 { timezone } 信封（版本化升级预留）
    const value = typeof parsed === 'string' ? parsed : (parsed as { timezone?: unknown })?.timezone;
    return isSupportedTimezone(value) ? value : 'system';
  } catch {
    return 'system';
  }
}

function persistTimezone(timezone: TimezonePref): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(timezone));
  } catch {
    // 存储不可用（隐私模式等）：仅保留内存态
  }
}

const persisted = loadPersistedTimezone();
// 启动联动（模块导入时执行一次）：持久化时区 → 日期 formatter 全局缺省
setTimezone(persisted);

export const useTimezoneStore = create<TimezoneStore>()((set) => ({
  timezone: persisted,
  setTimezone: (timezone) => {
    if (!isSupportedTimezone(timezone)) {
      return;
    }
    setTimezone(timezone);
    persistTimezone(timezone);
    set({ timezone });
  },
}));