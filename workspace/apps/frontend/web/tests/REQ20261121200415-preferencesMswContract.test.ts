/** @vitest-environment jsdom */
/**
 * REQ-20261121-200415（THEME-3）AC-12：web msw handlers 两域契约同形 + 校验拒绝矩阵。
 *
 * 表面 = 契约确认记录②③ + AC-12：
 *   GET  /admin/v1/system/me/preferences → 两域 { messagePush, themePreference }（未设行 themePreference=null）；
 *   PUT  按域 upsert（null=不动该域）；themePreference 顶层键白名单 schemaVersion(int)/preferences(object)/
 *        extensions(object,可省)；preferences 逐键白名单+类型+枚举+hex 正则；extensions 仅结构校验
 *        （object、单键 ≤16KB、总 ≤64KB）；非法 → HTTP 400 + 业务信封 code=SYS-01-0001
 *        （VALIDATION_FAILED 枚举的 wire 码，枚举名不上 wire；t3-backend 400 信封原文定稿）
 *        且原数据不动（fail-closed）。
 *
 * MSW 接入口径（REQ20261121022910-packagesMock.test.ts 先例）：
 *   - 复用项目交付的 handlers（src/mocks/handlers，与 dev mock 共用），不另写桩 handler；
 *   - jsdom docblock 提供 location 基座；msw/node setupServer + onUnhandledRequest: 'error' 钉死端点覆盖；
 *   - 经 core systemApi 偏好方法驱动（mePreferences / mePreferencesUpdate，契约变更清单「core api/system.ts 两域类型」），
 *     业务失败经 ApiError 拆包按错误码精确断言（wire 码契约钉死）。
 * fixture 口径（TL delta 裁决）：合法载荷按 UserPreferenceView/ThemePreferenceBlob 强类型字面量构造；
 *   拒绝矩阵负例刻意违反类型（坏枚举/未知键/坏型），在 API 边界单点 as unknown as 逃逸，语义不变。
 * 状态说明：handler 偏好 store 为模块级单例——同文件内用例按序执行，upsert 流程用例在前、
 *   拒绝矩阵用例各自 before/after 快照自证「原数据不动」，不依赖跨文件状态。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { ApiError, systemApi, useAuthStore } from '@acme/core';
import type { ThemePreferences, ThemePreferenceBlob, UserPreferenceView } from '@acme/core';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

/** ApiError 拆包（业务失败信封 → 异常）：负例断言走错误码精确匹配（packagesMock 范本口径）。 */
const expectApiError = async (invoke: () => Promise<unknown>, code: string): Promise<void> => {
  try {
    await invoke();
    throw new Error(`应抛出业务失败（${code}），实际成功返回`);
  } catch (ex: unknown) {
    expect(ex, `应抛 ApiError（${code}）`).toBeInstanceOf(ApiError);
    expect((ex as ApiError).code).toBe(code);
  }
};

const server = setupServer(...handlers);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useAuthStore.getState().setSession({
    accessToken: 'token-200415',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
});

afterEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

/** 合法偏好（与 core ThemePreferences 字段集对齐；可覆盖注入合法变体）。v4 适配=REQ-20261122-233427 确认②。 */
const validPrefs = (over: Partial<ThemePreferences> = {}): ThemePreferences => ({
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
  layoutMode: 'sidebar',
  tabStyle: 'line',
  siderTone: 'auto',
  topbarWidgets: {
    search: true, bell: true, lang: true, timezone: true, appearance: true,
    themeToggle: true, lock: true, fullscreen: true, settings: true,
  },
  ...over,
});

/** 合法 v3 blob（强类型字面量）。 */
const validBlob = (over: Partial<ThemePreferenceBlob> = {}): ThemePreferenceBlob => ({
  schemaVersion: 3,
  preferences: validPrefs(),
  ...over,
});

describe('REQ20261121200415 msw 两域契约同形（AC-12）', () => {
  it('should_return_two_domain_shape_on_get_when_preferences_endpoint_queried', async () => {
    const data = (await systemApi.mePreferences()) as UserPreferenceView;
    // 两域同形：messagePush + themePreference 同时存在（后端 AdminPreferenceView 扩域口径）
    expect('messagePush' in data).toBe(true);
    expect('themePreference' in data).toBe(true);
    expect(typeof data.messagePush).toBe('boolean');
    // 未设行 themePreference=null（契约④：后端不内置主题默认值）
    expect(data.themePreference).toBeNull();
  });

  it('should_upsert_by_domain_and_keep_other_domain_when_put_carries_theme_preference_only', async () => {
    // 第一步：两域 PUT（messagePush + themePreference）→ GET 回显两域
    const blobA = validBlob();
    await systemApi.mePreferencesUpdate({ messagePush: true, themePreference: blobA });

    const afterBoth = (await systemApi.mePreferences()) as UserPreferenceView;
    expect(afterBoth.messagePush).toBe(true);
    expect(afterBoth.themePreference).toEqual(blobA);

    // 第二步：仅 themePreference 域 PUT（messagePush=null=不动）→ messagePush 保持原值
    const blobB = validBlob({ preferences: validPrefs({ mode: 'light', accent: 'cyan', customAccent: null }) });
    await systemApi.mePreferencesUpdate({ messagePush: null, themePreference: blobB });

    const afterThemeOnly = (await systemApi.mePreferences()) as UserPreferenceView;
    expect(afterThemeOnly.messagePush).toBe(true);
    expect(afterThemeOnly.themePreference).toEqual(blobB);
  });
});

describe('REQ20261121200415 msw themePreference 校验拒绝矩阵（AC-12 fail-closed 对齐后端）', () => {
  it('should_reject_invalid_theme_preference_and_keep_original_data_when_matrix_committed', async () => {
    // 先落一份合法基线（原数据），逐条提交非法载荷并断言信封拒绝 + 原数据不动
    const baseline = validBlob();
    await systemApi.mePreferencesUpdate({ messagePush: false, themePreference: baseline });

    const oversized = 'x'.repeat(16 * 1024 + 1); // extensions 单键上限 16KB
    // 负例构造：刻意违反 ThemePreferenceBlob 类型（运行时校验面），边界单点逃逸（TL delta 裁决口径）
    const matrix: Array<[string, ThemePreferenceBlob]> = [
      ['未知顶层键', { ...validBlob(), rogueTopKey: 1 } as unknown as ThemePreferenceBlob],
      ['未知偏好键', { schemaVersion: 3, preferences: { ...validPrefs(), unknownPrefKey: true } } as unknown as ThemePreferenceBlob],
      ['坏枚举 mode', { schemaVersion: 3, preferences: { ...validPrefs(), mode: 'sepia' } } as unknown as ThemePreferenceBlob],
      ['坏 hex customAccent', { schemaVersion: 3, preferences: { ...validPrefs(), customAccent: 'b08968' } } as unknown as ThemePreferenceBlob],
      ['schemaVersion 类型非法', { schemaVersion: '3', preferences: validPrefs() } as unknown as ThemePreferenceBlob],
      ['extensions 单键超限', { schemaVersion: 3, preferences: validPrefs(), extensions: { big: { blob: oversized } } } as unknown as ThemePreferenceBlob],
    ];

    for (const [label, payload] of matrix) {
      const before = (await systemApi.mePreferences()) as UserPreferenceView;
      await expectApiError(
        () => systemApi.mePreferencesUpdate({ messagePush: null, themePreference: payload }),
        'SYS-01-0001',
      );
      // fail-closed：原数据不动
      const after = (await systemApi.mePreferences()) as UserPreferenceView;
      expect(after.themePreference, `${label} 拒绝后原数据不得被覆盖`).toEqual(before.themePreference);
      expect(after.messagePush, `${label} 拒绝后 messagePush 不得被覆盖`).toBe(before.messagePush);
    }
  });

  it('should_pass_through_valid_extensions_payload_when_structure_valid', async () => {
    // extensions 仅整体结构校验（object、限内），内容透传存储（契约③；未知域不拒绝）
    const blob = validBlob({ extensions: { foreignDomain: { anyShape: [1, 'x', { deep: true }] } } });
    await systemApi.mePreferencesUpdate({ messagePush: null, themePreference: blob });

    const data = (await systemApi.mePreferences()) as UserPreferenceView;
    expect(data.themePreference).toEqual(blob);
  });
});
