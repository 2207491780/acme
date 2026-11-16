import { create } from 'zustand';
import { isSupportedDateFormatTemplate, setDateFormatTemplate, type DateFormatTemplate } from '../date-format';

/**
 * 日期时间模板 store（日期国际化·模板维度，独立于语言与时区）：
 * - 持久化（key 版本化 acme.dateformat.v1，照 localeStore/timezoneStore 模式：运行时校验 + 存储异常降级内存态）；
 * - 缺省「跟随语言」（auto 哨兵，生效模板取当前 locale 预置）；
 * - setDateFormatTemplate 统一完成持久化 + date-format 全局缺省联动 + store 状态（React 重渲染）。
 */

const KEY = 'acme.dateformat.v1';

interface DateFormatStore {
  template: DateFormatTemplate;
  setTemplate: (template: DateFormatTemplate) => void;
}

/** 读取持久化模板：损坏/未知值/存储不可用一律回退缺省 auto（不阻塞应用）。 */
export function loadPersistedDateFormat(): DateFormatTemplate {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) {
      return 'auto';
    }
    const parsed: unknown = JSON.parse(raw);
    // 兼容两种落盘形状：裸字符串 或 { template } 信封（版本化升级预留）
    const value = typeof parsed === 'string' ? parsed : (parsed as { template?: unknown })?.template;
    return isSupportedDateFormatTemplate(value) ? value : 'auto';
  } catch {
    return 'auto';
  }
}

function persistTemplate(template: DateFormatTemplate): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(template));
  } catch {
    // 存储不可用（隐私模式等）：仅保留内存态
  }
}

const persisted = loadPersistedDateFormat();
// 启动联动（模块导入时执行一次）：持久化模板 → 日期 formatter 全局缺省
setDateFormatTemplate(persisted);

export const useDateFormatStore = create<DateFormatStore>()((set) => ({
  template: persisted,
  setTemplate: (template) => {
    if (!isSupportedDateFormatTemplate(template)) {
      return;
    }
    setDateFormatTemplate(template);
    persistTemplate(template);
    set({ template });
  },
}));