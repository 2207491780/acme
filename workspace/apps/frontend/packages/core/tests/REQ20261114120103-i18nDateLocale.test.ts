/** @vitest-environment jsdom */
/**
 * REQ-20261114-120103 AC-2：语言切换 → date-format locale 联动。
 *
 * 契约锚（§变更清单 前端 core）：「i18n 初始化（i18next 实例 + zh-CN/en-US 资源包 +
 * locale store 联动 date-format）」——联动归 core 职责，故用例落 packages/core/tests。
 * 输出断言复用既有 formatter 测试口径（packages/core/tests/date-format.test.ts /
 * REQ20261114071607-dateFormatter.test.ts 的锁定值）。
 *
 * 环境口径：jsdom docblock（useDict/fileTransfer 范本）——core barrel '../src' 会拉起
 * persist 型 store，node 环境无 localStorage 会炸；jsdom 提供真实 window/localStorage。
 *
 * 【表面假定（REQ20261114120103 套件内连续编号）】
 *   A-1 core 导出面存在 i18next 实例（changeLanguage+t 鸭子型定位，不猜导出名）；
 *       语言切换联动=对该实例 changeLanguage（与 UI 入口殊途同归，A-2）。
 *   A-5 date-format 导出面：formatDate/formatDateOnly/getDateLocale/setDateLocale 经
 *       '../src/date-format'（既有范本路径）；i18n 实例经 '../src' barrel 鸭子型获取。
 *   A-6 联动方向：i18n 语言变更 → locale store/date-format 全局缺省跟随
 *       （getDateLocale 反映 + formatDate 缺省输出随语言）；显式 locale 入参仍最高优先
 *       （既有 formatter 契约「显式 locale 优先于全局缺省」不被联动破坏）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as coreSrc from '../src';
import {
  formatDate,
  formatDateOnly,
  getDateLocale,
  setDateLocale,
} from '../src/date-format';

vi.setConfig({ testTimeout: 15000 });

// 用例均以本地时间构造输入，避免时区漂移（既有 formatter 测试口径）
const localTime = () => new Date(2026, 10, 14, 3, 5, 9); // 2026-11-14 03:05:09 本地时间

// ————— i18n 实例鸭子型定位（A-1，与 web 侧用例同型） —————
type I18nLike = {
  language?: string;
  changeLanguage: (lng: string) => Promise<unknown> | void;
  t: (key: string, opts?: unknown) => string;
};

const isI18nLike = (v: unknown): v is I18nLike =>
  !!v && typeof v === 'object'
  && typeof (v as I18nLike).changeLanguage === 'function'
  && typeof (v as I18nLike).t === 'function';

let cachedI18n: I18nLike | null = null;

const coreI18n = (): I18nLike => {
  if (cachedI18n) return cachedI18n;
  const mod = coreSrc as unknown as Record<string, unknown>;
  for (const value of Object.values(mod)) {
    if (isI18nLike(value)) {
      cachedI18n = value;
      return value;
    }
  }
  throw new Error('A-1 未命中：core 导出面未发现 i18next 实例（changeLanguage+t 鸭子型判定）');
};

const switchLang = async (lng: 'zh-CN' | 'en-US') => {
  await coreI18n().changeLanguage(lng);
};

afterEach(async () => {
  // 恢复全局缺省，防用例间串扰（date-format.test 口径 + i18n 单例复位）
  await switchLang('zh-CN');
  setDateLocale('zh-CN');
});

describe('REQ20261114120103 语言切换 → date-format locale 联动（AC-2）', () => {
  it('should_follow_language_switch_when_date_locale_linked', async () => {
    // 基线钉死 zh-CN
    await switchLang('zh-CN');
    expect(getDateLocale()).toBe('zh-CN');
    expect(formatDate(localTime())).toBe('2026-11-14 03:05:09');

    // 切换 en-US：locale store 联动 → 全局缺省输出随语言（锁定值复用既有 formatter 口径）
    await switchLang('en-US');
    expect(getDateLocale()).toBe('en-US');
    expect(formatDate(localTime())).toBe('Nov 14, 2026 3:05:09 AM');

    // 切回 zh-CN：联动可逆
    await switchLang('zh-CN');
    expect(getDateLocale()).toBe('zh-CN');
    expect(formatDate(localTime())).toBe('2026-11-14 03:05:09');
  });

  it('should_keep_explicit_locale_option_priority_when_language_switched', async () => {
    await switchLang('en-US');
    expect(getDateLocale()).toBe('en-US');
    // 显式 locale 优先于全局缺省（既有 formatter 契约；联动不得破坏该优先级，A-6）
    expect(formatDate(localTime(), { locale: 'zh-CN' })).toBe('2026-11-14 03:05:09');
    expect(formatDateOnly(localTime(), { locale: 'en-US' })).toBe('Nov 14, 2026');
  });
});
