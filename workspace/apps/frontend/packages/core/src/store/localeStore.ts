import { create } from 'zustand';
import { setDateLocale } from '../date-format';
import { i18n, initI18n, isSupportedLocale, type AppLocale } from '../i18n';
import { loadPersistedPreference, persistPreference } from './persist';

/**
 * 展示 locale store（REQ-20261114-120103，确认记录①）：
 * - locale 持久化（key 版本化 acme.locale.v1，照 authStore 模式：运行时校验 + 存储异常降级内存态）；
 * - 语言切换统一以 i18n.changeLanguage 为单一动因（Header/Settings 入口与测试直呼殊途同归）：
 *   languageChanged 订阅统一完成三处联动——持久化 + setDateLocale（AC-2）+ store 状态（React 重渲染）。
 */

const KEY = 'acme.locale.v1';

interface LocaleStore {
  locale: AppLocale;
  setLocale: (locale: AppLocale) => void;
}

/** 读取持久化 locale：损坏/未知值/存储不可用一律回退缺省 zh-CN（不阻塞应用）。 */
export function loadPersistedLocale(): AppLocale {
  return loadPersistedPreference(KEY, 'locale', 'zh-CN', isSupportedLocale);
}

function persistLocale(locale: AppLocale): void {
  persistPreference(KEY, locale);
}

const persisted = loadPersistedLocale();
// 启动联动（模块导入时执行一次）：持久化 locale → i18n（initI18n 幂等，changeLanguage 兜齐）+ 日期 formatter
initI18n(persisted);
void i18n.changeLanguage(persisted);
setDateLocale(persisted);

export const useLocaleStore = create<LocaleStore>()(() => ({
  locale: persisted,
  setLocale: (locale) => {
    if (!isSupportedLocale(locale)) {
      return;
    }
    // 单一动因：切换细节（持久化/日期联动/状态同步）统一由 languageChanged 订阅完成
    void i18n.changeLanguage(locale);
  },
}));

// 任意来源的 language 切换（Header/Settings 入口、或外部直呼 changeLanguage）统一联动：
// 持久化 + 日期 formatter + store 状态（AC-1/AC-2；订阅在 store 建立后注册，杜绝 TDZ）
i18n.on('languageChanged', (lng) => {
  if (!isSupportedLocale(lng) || lng === useLocaleStore.getState().locale) {
    return;
  }
  persistLocale(lng);
  setDateLocale(lng);
  useLocaleStore.setState({ locale: lng });
});
