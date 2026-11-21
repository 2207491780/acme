/**
 * REQ-20261121-200415（THEME-3）AC-5：core 扩展偏好注册机制契约用例。
 *
 * 表面 = 契约确认记录⑤（导出面钉死，勿臆造）：
 *   registerPreferenceExtension(key, spec)（spec = { defaults, parse }，parse 非法降级 defaults 不抛错）；
 *   preferences 增可选 extensions 键（仅当存在注册域时出现）；
 *   validateThemePreferences 对注册域调用 spec.parse 降级、未注册域透传存储；
 *   注册面钉死为最小机制（禁框架化）。AC-12 后端仅对 extensions 做结构校验（内容透传），不在本文件。
 *
 * 装配：node 环境纯函数单测（照 core 既有 preferences 范本 REQ20261121010725 口径）。
 * 注册表为模块级全局：本文件内各用例使用互不相同的域 key，避免跨用例注册泄漏互染；
 * 契约未钉死反注册 API，不做卸载断言。
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_VERSION,
  registerPreferenceExtension,
  validateThemePreferences,
  type ThemePreferences,
} from '../src';

/** 合法基线偏好（照 010725 范本 validPreferences 口径，v3 全字段）。 */
const validPreferences = (over: Partial<ThemePreferences> | Record<string, unknown> = {}): ThemePreferences => ({
  mode: 'dark',
  accent: 'emerald',
  customAccent: '#b08968',
  density: 'compact',
  motionEnabled: false,
  radius: 'medium',
  grayMode: false,
  weakMode: false,
  contentWidth: 'fluid',
  semanticColors: { success: null, warning: null, danger: null },
  ...(over as Partial<ThemePreferences>),
});

describe('REQ20261121200415 扩展注册机制（AC-5）', () => {
  it('should_omit_extensions_key_when_no_domain_registered', () => {
    // 注册前：输入携带 extensions 内容也不得产出 extensions 键（契约⑤「仅当存在注册域时出现」）
    const result = validateThemePreferences({
      ...validPreferences(),
      extensions: { anything: { a: 1 } },
    }) as unknown as Record<string, unknown>;

    expect('extensions' in result).toBe(false);
    // 其余偏好字段不受 extensions 输入影响
    expect(result['mode']).toBe('dark');
    expect(result['accent']).toBe('emerald');
  });

  it('should_degrade_registered_extension_to_defaults_when_value_invalid', () => {
    // 域 key 用例内独占（注册表全局，防跨用例互染）
    registerPreferenceExtension('widgetLayout', {
      defaults: { pinned: ['a'], column: 2 },
      parse: (raw: unknown) => {
        // parse 自降级口径：非法输入返回 defaults（契约⑤「parse 非法降级 defaults」）
        if (typeof raw !== 'object' || raw === null) {
          return { pinned: ['a'], column: 2 };
        }
        const r = raw as Record<string, unknown>;
        return { pinned: Array.isArray(r['pinned']) ? r['pinned'] : ['a'], column: r['column'] === 2 ? 2 : 2 };
      },
    });

    // 注册后：键出现（defaults 物化）；非法值经 parse 降级为 defaults，不抛错
    const result = validateThemePreferences({
      ...validPreferences(),
      extensions: { widgetLayout: 'not-an-object' },
    }) as unknown as Record<string, unknown>;

    expect('extensions' in result).toBe(true);
    const extensions = result['extensions'] as Record<string, unknown>;
    expect(extensions['widgetLayout']).toEqual({ pinned: ['a'], column: 2 });
  });

  it('should_fall_back_to_defaults_without_throwing_when_parse_rejects', () => {
    registerPreferenceExtension('notesPanel', {
      defaults: { collapsed: false },
      parse: (raw: unknown) => {
        // parse 抛错口径：注册机制须兜底降级 defaults，不得向调用方抛出
        if (typeof raw !== 'object' || raw === null || !('collapsed' in (raw as object))) {
          throw new Error('invalid notes payload');
        }
        return raw as { collapsed: boolean };
      },
    });

    let result: Record<string, unknown> | undefined;
    expect(() => {
      result = validateThemePreferences({
        ...validPreferences(),
        extensions: { notesPanel: { unexpected: true } },
      }) as unknown as Record<string, unknown>;
    }).not.toThrow();

    const extensions = result!['extensions'] as Record<string, unknown>;
    expect(extensions['notesPanel']).toEqual({ collapsed: false });
  });

  it('should_materialize_registered_defaults_when_input_has_no_extensions', () => {
    registerPreferenceExtension('sidebarOrder', {
      defaults: { order: ['home', 'users'] },
      parse: (raw: unknown) => raw,
    });

    // 注册后即使输入无 extensions，键也应出现（契约⑤「仅当存在注册域时出现」+defaults 兜底）
    const result = validateThemePreferences(validPreferences()) as unknown as Record<string, unknown>;
    expect('extensions' in result).toBe(true);
    expect((result['extensions'] as Record<string, unknown>)['sidebarOrder']).toEqual({
      order: ['home', 'users'],
    });
  });

  it('should_passthrough_unregistered_extension_domain_when_stored', () => {
    registerPreferenceExtension('widgetLayout2', {
      defaults: { pinned: [] },
      parse: (raw: unknown) => raw,
    });

    const foreign = { nested: { deep: [1, 2, 3] }, flag: true };
    const result = validateThemePreferences({
      ...validPreferences(),
      extensions: { widgetLayout2: { pinned: ['x'] }, foreignDomain: foreign },
    }) as unknown as Record<string, unknown>;

    const extensions = result['extensions'] as Record<string, unknown>;
    // 注册域走 parse；未注册域透传存储（内容原样保留，不做结构改造）
    expect(extensions['widgetLayout2']).toEqual({ pinned: ['x'] });
    expect(extensions['foreignDomain']).toEqual(foreign);
  });

  it('should_keep_core_preferences_untouched_when_extensions_processed', () => {
    registerPreferenceExtension('coreGuard', {
      defaults: { on: false },
      parse: (raw: unknown) => raw,
    });

    // extensions 处理不污染核心偏好字段，也不引入投影字段残留
    const result = validateThemePreferences({
      ...validPreferences({ mode: 'sepia', customAccent: 'bad-hex' }),
      extensions: { coreGuard: { on: true } },
    }) as unknown as Record<string, unknown>;

    expect(result['mode']).toBe('light'); // 非法 mode 照常逐字段降级
    expect(result['customAccent']).toBeNull(); // 非法 hex 照常置 null
    // 注册表为文件级全局：本域经 parse 透传保留；先前用例注册的域以 defaults 物化（契约⑤口径）
    expect((result['extensions'] as Record<string, unknown>)['coreGuard']).toEqual({ on: true });
  });
});

describe('REQ20261121200415 扩展与版本常量（AC-5 前置锚）', () => {
  it('should_keep_version_and_defaults_pinned_when_extension_registry_active', () => {
    // 注册机制不得改变版本常量与默认值事实源（防双源漂移，契约④⑤）
    expect(PREFERENCES_VERSION).toBe(3);
    expect(DEFAULT_PREFERENCES).toEqual({
      mode: 'light',
      accent: 'cyan',
      customAccent: null,
      density: 'comfortable',
      motionEnabled: true,
      radius: 'medium',
      grayMode: false,
      weakMode: false,
      contentWidth: 'fluid',
      semanticColors: { success: null, warning: null, danger: null },
    });
  });
});
