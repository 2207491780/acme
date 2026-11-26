/** @vitest-environment jsdom */
/**
 * REQ-20261122-233427（THEME-4）AC-8 之 msm 面：web msw preferences 载荷 v4 同形。
 *
 * 表面 = 契约确认记录②⑧ + 委派单钉死事实（msm/preferences PUT 载荷={schemaVersion, preferences}）：
 *   - PUT themePreference v4 载荷（schemaVersion=4 + v3 字段 + layoutMode/tabStyle/siderTone/
 *     topbarWidgets）→ GET 回显同形；
 *   - v3 旧载荷（schemaVersion=3 + v3 字段集）兼容通过（AC-8 兼容面）；
 *   - v4 非法值（坏枚举/未知 WidgetKey/topbarWidgets 非对象）→ HTTP 400 + 业务信封
 *     code=SYS-01-0001（VALIDATION_FAILED wire 码，200415 定稿口径）且原数据不动（fail-closed）。
 *
 * MSW 接入口径照范本 web/tests/REQ20261121200415-preferencesMswContract.test.ts：
 *   复用项目交付 handlers（src/mocks/handlers）+ msw/node setupServer + onUnhandledRequest:'error' +
 *   经 core systemApi 驱动（mePreferences / mePreferencesUpdate）+ ApiError 按错误码精确断言。
 * fixture 口径（200415 TL delta 裁决延续）：合法载荷强类型字面量构造（v4 字段随 core 类型落地自然合型）；
 *   负例刻意违反类型，在 API 边界单点 as unknown as 逃逸，语义不变。
 * 状态说明：handler 偏好 store 为模块级单例——同文件内用例按序执行，upsert 在前、
 *   拒绝矩阵各自 before/after 快照自证「原数据不动」，不依赖跨文件状态。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { ApiError, systemApi, useAuthStore } from '@acme/core';
import type { ThemePreferenceBlob, UserPreferenceView } from '@acme/core';
import { handlers } from '../src/mocks/handlers';

// 15s 上限（200415 范本口径；msw node + jsdom 装配首包较重）
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
    accessToken: 'token-233427',
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

/** WidgetKey 枚举 = 契约②原文逐字（十键）。 */
const WIDGET_KEYS = [
  'search', 'bell', 'lang', 'timezone', 'appearance',
  'themeToggle', 'lock', 'fullscreen', 'settings',
] as const;

const allWidgetsVisible = (): Record<string, boolean> =>
  Object.fromEntries(WIDGET_KEYS.map((key) => [key, true]));

/** 合法偏好基线（v3 字段全量非默认样本 + 可覆盖；v4 字段由 over 注入）。 */
const basePrefs = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
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
  ...over,
});

/** 合法 v4 载荷（{schemaVersion, preferences} 同形，200415 validBlob 口径抬到 v4）。 */
const validV4Blob = (prefsOver: Record<string, unknown> = {}): Record<string, unknown> => ({
  schemaVersion: 4,
  preferences: basePrefs({
    layoutMode: 'mixed',
    tabStyle: 'card',
    siderTone: 'dark',
    topbarWidgets: { ...allWidgetsVisible(), search: false },
    ...prefsOver,
  }),
});

/** 合法 v3 旧载荷（schemaVersion=3 + v3 字段集，无 v4 字段——AC-8 兼容面）。 */
const validV3Blob = (): Record<string, unknown> => ({
  schemaVersion: 3,
  preferences: basePrefs(),
});

describe('REQ20261122233427 msm preferences v4 载荷同形（AC-8 msm 面）', () => {
  it('should_upsert_and_echo_v4_blob_when_put_carries_v4_preferences', async () => {
    // PUT v4 载荷（形状={schemaVersion, preferences}）→ GET 回显逐字段同形
    const blob = validV4Blob();
    await systemApi.mePreferencesUpdate({
      messagePush: true,
      themePreference: blob as unknown as ThemePreferenceBlob,
    });

    const data = (await systemApi.mePreferences()) as UserPreferenceView;
    expect(data.themePreference).toEqual(blob);
    const echoed = data.themePreference as unknown as Record<string, unknown>;
    expect(Object.keys(echoed).sort()).toEqual(['preferences', 'schemaVersion']);
    const prefs = echoed['preferences'] as Record<string, unknown>;
    expect(prefs['layoutMode']).toBe('mixed');
    expect(prefs['tabStyle']).toBe('card');
    expect(prefs['siderTone']).toBe('dark');
    expect(prefs['topbarWidgets']).toEqual({ ...allWidgetsVisible(), search: false });
    expect(data.messagePush).toBe(true);
  });

  it('should_accept_v3_legacy_blob_when_version_not_bumped', async () => {
    // v3 旧载荷兼容（AC-8）：schemaVersion=3 + v3 字段集 → 通过并回显
    const blob = validV3Blob();
    await systemApi.mePreferencesUpdate({
      messagePush: null,
      themePreference: blob as unknown as ThemePreferenceBlob,
    });

    const data = (await systemApi.mePreferences()) as UserPreferenceView;
    expect(data.themePreference).toEqual(blob);
  });
});

describe('REQ20261122233427 msm v4 校验拒绝矩阵（AC-8 fail-closed 对齐后端）', () => {
  it('should_reject_invalid_v4_payload_and_keep_original_data_when_matrix_committed', async () => {
    // 先落一份合法 v4 基线（原数据），逐条提交非法 v4 载荷并断言信封拒绝 + 原数据不动
    const baseline = validV4Blob();
    await systemApi.mePreferencesUpdate({ messagePush: false, themePreference: baseline as unknown as ThemePreferenceBlob });

    // 负例构造：刻意违反 v4 校验面（坏枚举/未知 WidgetKey/非对象），边界单点逃逸（200415 TL delta 裁决口径）
    const matrix: Array<[string, Record<string, unknown>]> = [
      ['坏枚举 layoutMode', validV4Blob({ layoutMode: 'float' })],
      ['坏枚举 tabStyle', validV4Blob({ tabStyle: 'ribbon' })],
      ['坏枚举 siderTone', validV4Blob({ siderTone: 'midnight' })],
      ['topbarWidgets 非对象', validV4Blob({ topbarWidgets: 42 })],
      ['未知 WidgetKey', validV4Blob({ topbarWidgets: { ...allWidgetsVisible(), rogueWidget: true } })],
    ];

    for (const [label, payload] of matrix) {
      const before = (await systemApi.mePreferences()) as UserPreferenceView;
      await expectApiError(
        () => systemApi.mePreferencesUpdate({
          messagePush: null,
          themePreference: payload as unknown as ThemePreferenceBlob,
        }),
        'SYS-01-0001',
      );
      // fail-closed：原数据不动
      const after = (await systemApi.mePreferences()) as UserPreferenceView;
      expect(after.themePreference, `${label} 拒绝后原数据不得被覆盖`).toEqual(before.themePreference);
      expect(after.messagePush, `${label} 拒绝后 messagePush 不得被覆盖`).toBe(before.messagePush);
    }
  });
});
