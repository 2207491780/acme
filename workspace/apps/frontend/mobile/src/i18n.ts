// Mobile i18n（契约③）：直接消费 core 的 i18next 实例（@acme/core barrel 已同步初始化），
// mobile 不再装 i18n 包（i18next/react-i18next 不进 mobile 依赖清单）。
// locale 状态消费 core localeStore（语言切换单一动因 = i18n.changeLanguage，languageChanged
// 订阅统一联动持久化/日期/store 状态），本 hook 经 zustand 订阅实现语言切换即时重渲染。
import { useCallback } from 'react';
import { i18n, useLocaleStore } from '@acme/core';
import type { AppLocale } from '@acme/core';

export type { AppLocale };

export type TFunction = (key: string, vars?: Record<string, unknown>) => string;

export interface TranslationResult {
  t: TFunction;
  locale: AppLocale;
}

/**
 * useTranslation（react-i18next useTranslation 同形 API 的轻量替身）：t 缺省即当前 locale，
 * 插值变量与 i18next 原生一致（{{name}} 形式）。
 */
export function useTranslation(): TranslationResult {
  const locale = useLocaleStore((s) => s.locale);
  const t = useCallback<TFunction>(
    (key, vars) => i18n.t(key, { lng: locale, ...(vars ?? {}) }) as string,
    [locale],
  );
  return { t, locale };
}

export { i18n };
