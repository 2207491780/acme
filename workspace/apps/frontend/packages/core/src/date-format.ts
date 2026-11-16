import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import 'dayjs/locale/zh-cn';
import 'dayjs/locale/en';

dayjs.extend(utc);
dayjs.extend(timezone);

/**
 * 统一日期 formatter（日期国际化唯一入口）：
 * - dayjs 为显式依赖（收编 web 经 AntD 的传递使用）；
 * - locale 支持 zh-CN / en-US，输出模板按 locale 预置且可经 options.format 覆盖；
 * - 时区支持「跟随系统」或 IANA 时区（SUPPORTED_TIMEZONES），全站日期展示随所选时区换算；
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

/** 「跟随系统」时区哨兵值（生效时区取运行环境本地时区）。 */
export const SYSTEM_TIMEZONE = 'system';

/** 可选时区（跟随系统 + 常用 IANA 时区；设置页下拉与持久化校验共用）。 */
export const SUPPORTED_TIMEZONES = [
  SYSTEM_TIMEZONE,
  'UTC',
  'Asia/Shanghai',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Europe/London',
  'Europe/Paris',
  'America/New_York',
  'America/Los_Angeles',
  'Australia/Sydney',
] as const;

export type TimezonePref = (typeof SUPPORTED_TIMEZONES)[number];

export function isSupportedTimezone(value: unknown): value is TimezonePref {
  return typeof value === 'string' && (SUPPORTED_TIMEZONES as readonly string[]).includes(value);
}

let defaultTimezone: TimezonePref = SYSTEM_TIMEZONE;

/** 设置全局缺省时区偏好（timezoneStore 联动调用；缺省跟随系统）。 */
export function setTimezone(tz: TimezonePref): void {
  defaultTimezone = tz;
}

/** 读取当前时区偏好（可能为 'system' 哨兵）。 */
export function getTimezone(): TimezonePref {
  return defaultTimezone;
}

/** 当前生效 IANA 时区（'system' → 运行环境本地时区）。 */
export function effectiveTimezone(): string {
  return defaultTimezone === SYSTEM_TIMEZONE
    ? Intl.DateTimeFormat().resolvedOptions().timeZone
    : defaultTimezone;
}

/** 生效时区下的 dayjs 实例（图表按日分桶等需要时区感知的日期运算统一经此出口）。 */
export function zonedDayjs(value?: DateInput): dayjs.Dayjs {
  return dayjs(value).tz(effectiveTimezone());
}

/** 「跟随语言」模板哨兵值（生效模板取当前 locale 预置）。 */
export const AUTO_DATE_FORMAT = 'auto';

/** 可选日期时间模板（跟随语言 + 常用 dayjs 模板；设置页下拉与持久化校验共用）。 */
export const DATE_FORMAT_TEMPLATES = [
  AUTO_DATE_FORMAT,
  'YYYY-MM-DD HH:mm:ss',
  'YYYY/MM/DD HH:mm',
  'DD/MM/YYYY HH:mm',
  'MM/DD/YYYY h:mm A',
  'MMM D, YYYY h:mm:ss A',
] as const;

export type DateFormatTemplate = (typeof DATE_FORMAT_TEMPLATES)[number];

export function isSupportedDateFormatTemplate(value: unknown): value is DateFormatTemplate {
  return typeof value === 'string' && (DATE_FORMAT_TEMPLATES as readonly string[]).includes(value);
}

let defaultTemplate: DateFormatTemplate = AUTO_DATE_FORMAT;

/** 设置全局日期时间模板偏好（dateFormatStore 联动调用；缺省跟随语言）。 */
export function setDateFormatTemplate(template: DateFormatTemplate): void {
  defaultTemplate = template;
}

/** 读取当前日期时间模板偏好（可能为 'auto' 哨兵）。 */
export function getDateFormatTemplate(): DateFormatTemplate {
  return defaultTemplate;
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
  useTemplate: boolean,
): string {
  if (value === null || value === undefined || value === '') {
    return DATE_PLACEHOLDER;
  }
  const d = zonedDayjs(value);
  if (!d.isValid()) {
    return DATE_PLACEHOLDER;
  }
  const locale = options?.locale ?? defaultLocale;
  // 优先级：显式 format > 用户模板偏好（仅日期时间列）> locale 预置
  const template = useTemplate && defaultTemplate !== AUTO_DATE_FORMAT ? defaultTemplate : presets[locale];
  return d.locale(DAYJS_LOCALES[locale]).format(options?.format ?? template);
}

/** 日期 formatter 统一出口（管理页 createdAt/updatedAt/occurredAt 等日期时间列）。 */
export function formatDate(value: DateInput, options?: DateFormatOptions): string {
  return formatWith(value, options, DATETIME_FORMATS, true);
}

/** 纯日期展示（无时间部分；不受日期时间模板偏好影响，仍随 locale 预置）。 */
export function formatDateOnly(value: DateInput, options?: DateFormatOptions): string {
  return formatWith(value, options, DATE_FORMATS, false);
}
