/**
 * 菜单图标注册表（menuIcons）：选择器与导航渲染共用事实源的一致性回归。
 * 标识即后端 menu.icon 存储值；种子标识保持历史口径，注册表外标识统一回退。
 */
import { describe, expect, it } from 'vitest';
import { MENU_ICON_OPTIONS, resolveMenuIcon } from '../src/menuIcons';

/** nav.tsx 历史 MENU_ICON_MAP 的种子标识（扩展不得破坏）。 */
const LEGACY_CODES = [
  'dashboard', 'releases', 'apps', 'tenants', 'users', 'roles', 'depts', 'positions',
  'permissions', 'menus', 'dicts', 'audit', 'loginlogs', 'onlineusers', 'files', 'settings',
];

describe('menuIcons 注册表', () => {
  it('should_have_unique_codes_when_options_listed', () => {
    const codes = MENU_ICON_OPTIONS.map((o) => o.code);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('should_resolve_every_option_code_when_registered', () => {
    for (const option of MENU_ICON_OPTIONS) {
      expect(resolveMenuIcon(option.code), `标识 ${option.code} 未解析到注册图标`).toBe(option.icon);
    }
  });

  it('should_keep_legacy_seed_codes_when_registry_extended', () => {
    for (const code of LEGACY_CODES) {
      const option = MENU_ICON_OPTIONS.find((o) => o.code === code);
      expect(option, `种子标识 ${code} 丢失`).toBeTruthy();
      expect(resolveMenuIcon(code)).toBe(option!.icon);
    }
  });

  it('should_fallback_when_icon_unknown_or_empty', () => {
    const fallback = resolveMenuIcon(null);
    expect(resolveMenuIcon('no-such-icon')).toBe(fallback);
    expect(resolveMenuIcon('')).toBe(fallback);
  });

  it('should_resolve_case_insensitively_when_code_mixed_case', () => {
    expect(resolveMenuIcon('BELL')).toBe(resolveMenuIcon('bell'));
  });

  it('should_group_by_source_when_lucide_registry_added', () => {
    const antd = MENU_ICON_OPTIONS.filter((o) => o.source === 'antd');
    const lucide = MENU_ICON_OPTIONS.filter((o) => o.source === 'lucide');
    expect(antd.length).toBe(200);
    expect(lucide.length).toBeGreaterThanOrEqual(200);
    expect(lucide.every((o) => o.code.startsWith('lucide-'))).toBe(true);
    expect(antd.every((o) => !o.code.startsWith('lucide-'))).toBe(true);
    const cat = lucide.find((o) => o.code === 'lucide-cat');
    expect(cat, 'lucide-cat 未注册').toBeTruthy();
    expect(resolveMenuIcon('LUCIDE-CAT')).toBe(cat!.icon);
  });
});