import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import 'dayjs/locale/en';

/**
 * 统一日期 formatter（日期国际化唯一入口）：
 * - dayjs 为显式依赖（收编 web 经 AntD 的传递使用）；
 * - locale 支持 zh-CN / en-US，输出模板按 locale 预置且可经 options.format 覆盖；
 * - 无效/空值统一回退占位符 '—'（与既有列表列口径一致）。
 * 全站文案 i18n 归后续 REQ，本模块只负责日期列展示。
 */

/** 支持的展示 locale（后续 REQ 接入文案 i18n 时复用同一枚举）。 */
export type DateLocale = 'zh-CN' | 'en-US';

/** DateLocale → dayjs locale 包名映射（en 为 dayjs 内建缺省 locale）。 */
const DAYJS_LOCALES: Record<DateLocale, string> = {
  'zh-CN': 'zh-cn',
  'en-US': 'en',
};

/** 各 locale 缺省日期时间模板（YYYY-MM-DD HH:mm:ss / Nov 14, 2026 3:05 PM）。 */
export const DATETIME_FORMATS: Record<DateLocale, string> = {
  'zh-CN': 'YYYY-MM-DD HH:mm:ss',
  'en-US': 'MMM D, YYYY h:mm:ss A',
};

/** 各 locale 缺省纯日期模板（YYYY-MM-DD / Nov 14, 2026）。 */
export const DATE_FORMATS: Record<DateLocale, string> = {
  'zh-CN': 'YYYY-MM-DD',
  'en-US': 'MMM D, YYYY',
};

/** 空/无效值占位符（与列表列既有 '—' 口径一致）。 */
export const DATE_PLACEHOLDER = '—';

let defaultLocale: DateLocale = 'zh-CN';

/** 设置全局缺省 locale（后续接入设置页语言切换时调用；缺省 zh-CN）。 */
export function setDateLocale(locale: DateLocale): void {
  defaultLocale = locale;
}

/** 读取当前全局缺省 locale。 */
export function getDateLocale(): DateLocale {
  return defaultLocale;
}

export interface DateFormatOptions {
  /** 覆盖全局缺省 locale（显式传入优先）。 */
  locale?: DateLocale;
  /** 覆盖 locale 预置模板（dayjs format 模板）。 */
  format?: string;
}

/** 可接受的时间输入（ISO-8601 字符串 / epoch 毫秒 / Date）。 */
export type DateInput = string | number | Date | null | undefined;

function formatWith(
  value: DateInput,
  options: DateFormatOptions | undefined,
  presets: Record<DateLocale, string>,
): string {
  if (value === null || value === undefined || value === '') {
    return DATE_PLACEHOLDER;
  }
  const d = dayjs(value);
  if (!d.isValid()) {
    return DATE_PLACEHOLDER;
  }
  const locale = options?.locale ?? defaultLocale;
  return d.locale(DAYJS_LOCALES[locale]).format(options?.format ?? presets[locale]);
}

/** 日期 formatter 统一出口（管理页 createdAt/updatedAt/occurredAt 等日期时间列）。 */
export function formatDate(value: DateInput, options?: DateFormatOptions): string {
  return formatWith(value, options, DATETIME_FORMATS);
}

/** 纯日期展示（无时间部分）。 */
export function formatDateOnly(value: DateInput, options?: DateFormatOptions): string {
  return formatWith(value, options, DATE_FORMATS);
}
