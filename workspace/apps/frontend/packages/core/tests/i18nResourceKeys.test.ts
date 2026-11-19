import { describe, expect, it } from 'vitest';
import { enUS } from '../src/i18n/resources/en-US';
import { zhCN } from '../src/i18n/resources/zh-CN';

/**
 * i18n 资源键一致性校验（脚本化，S14）：zh-CN 基线与 en-US 的资源树必须同构——
 * 任一语言新增/删除键而另一语言遗漏时在测试期即失败，防止线上依赖 i18next
 * 回退链路静默展示错误语言的键名。叶子路径 = 点分键全路径（空对象/空数组视作叶子）。
 */

/** 递归收集资源树叶子路径（值非纯对象即叶子；数组/空对象视作叶子占位）。 */
function leafPaths(node: unknown, prefix = ''): string[] {
  if (node === null || typeof node !== 'object') {
    return prefix ? [prefix] : [];
  }
  const entries = Object.entries(node as Record<string, unknown>);
  if (entries.length === 0) {
    return prefix ? [prefix] : [];
  }
  return entries.flatMap(([key, value]) => leafPaths(value, prefix ? `${prefix}.${key}` : key));
}

describe('i18n 资源键一致性（zh-CN ↔ en-US）', () => {
  it('should_have_identical_key_sets_when_comparing_zhCN_and_enUS', () => {
    const zhKeys = leafPaths(zhCN).sort();
    const enKeys = leafPaths(enUS).sort();
    const missingInEn = zhKeys.filter((key) => !enKeys.includes(key));
    const missingInZh = enKeys.filter((key) => !zhKeys.includes(key));
    expect(missingInEn, 'en-US 缺失的键（zh-CN 基线存在）').toEqual([]);
    expect(missingInZh, 'zh-CN 缺失的键（en-US 存在）').toEqual([]);
    expect(zhKeys.length).toBeGreaterThan(0);
  });
});
