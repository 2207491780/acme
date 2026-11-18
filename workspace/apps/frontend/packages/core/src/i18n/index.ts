import i18next, { type i18n as I18nInstance } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { zhCN } from './resources/zh-CN';
import { enUS } from './resources/en-US';
import type { DateLocale } from '../date-format';

/**
 * 全站文案 i18n 实例（REQ-20261114-120103，裁决①=i18next 全量迁移）：
 * - 资源包按域拆分（resources/zh-CN/*、resources/en-US/*），zh-CN 基线=既有硬编码原文；
 * - 内联资源同步初始化（无异步 loader，`init()` 即刻完成，useTranslation 无 Suspense 挂起）；
 * - locale 枚举复用 date-format 的 DateLocale（zh-CN / en-US），与日期 formatter 同源联动；
 * - Web 层经 useTranslation() 消费；locale 切换由 localeStore 统一驱动（i18n.changeLanguage +
 *   setDateLocale 同步联动）。
 */

export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'] as const;

/** 应用展示 locale（复用日期域枚举：zh-CN / en-US）。 */
export type AppLocale = DateLocale;

/** DateLocale → i18n 资源命名空间标识。 */
const RESOURCE_BUNDLES: Record<AppLocale, Record<string, unknown>> = {
  'zh-CN': zhCN as unknown as Record<string, unknown>,
  'en-US': enUS as unknown as Record<string, unknown>,
};

export const DEFAULT_LOCALE: AppLocale = 'zh-CN';

/** 模块级单例（react-i18next useTranslation 缺省消费同一实例）。 */
export const i18n: I18nInstance = i18next.createInstance();

let initialized = false;

/**
 * 幂等初始化：内联资源同步 init（React 层 useSuspense=false，避免无 Suspense 边界时的挂起）。
 * lng 取值由调用方决定：应用入口传持久化 locale（localeStore），测试/SSR 可缺省 zh-CN。
 */
export function initI18n(locale: AppLocale = DEFAULT_LOCALE): I18nInstance {
  if (!initialized) {
    void i18n.use(initReactI18next).init({
      resources: {
        'zh-CN': { translation: RESOURCE_BUNDLES['zh-CN'] },
        'en-US': { translation: RESOURCE_BUNDLES['en-US'] },
      },
      lng: locale,
      fallbackLng: DEFAULT_LOCALE,
      supportedLngs: SUPPORTED_LOCALES as unknown as string[],
      defaultNS: 'translation',
      interpolation: { escapeValue: false },
      returnNull: false,
      react: { useSuspense: false },
    });
    initialized = true;
  }
  return i18n;
}

// 模块导入即完成同步初始化：所有经 @acme/core 引入 i18n 的入口（web 应用/组件测试）均拿到就绪实例。
initI18n();

/** 运行时校验外部 locale 值（持久化数据不信任，任一未知值回退缺省）。 */
export function isSupportedLocale(value: unknown): value is AppLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}
