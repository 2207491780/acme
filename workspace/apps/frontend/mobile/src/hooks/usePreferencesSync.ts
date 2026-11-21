import { useEffect, useRef, useState } from 'react';
import { migrateSettings, PREFERENCES_VERSION } from '@acme/core';
import { apiFetch, apiJson } from '../api/client';
import { useAuthStore } from '../store/authStore';
import { useStore } from '../store/useStore';

/** 偏好上行防抖毫秒数（REQ-20261121-200415 契约⑥⑨钉死：800ms，与 web 同口径）。 */
const PREFERENCES_PUT_DEBOUNCE_MS = 800;

/** 主题偏好 blob（对齐 core ThemePreferenceBlob：schemaVersion + preferences）。 */
function buildThemePreferenceBlob() {
  const preferences = useStore.getState().state.settings.preferences;
  return { schemaVersion: PREFERENCES_VERSION, preferences };
}

function serializePreferences(preferences: unknown): string {
  return JSON.stringify(preferences);
}

/**
 * 偏好后端持久化接线（REQ-20261121-200415 契约⑨，与 web 契约⑥同口径）：
 * - 登录成功（含冷启动恢复会话）→ GET preferences：themePreference 非 null 经 migrateSettings
 *   覆盖本地 preferences（后端权威）；null=后端未设 → AsyncStorage STORAGE_KEY 单 blob 本地兜底；
 * - 偏好变更即时生效（UPDATE_SETTINGS 即落 STORAGE_KEY 单 blob，沿用既有持久化管道，不新增键）
 *   + 防抖 800ms 单次 PUT themePreference（按域 upsert，messagePush 域不触碰）；
 * - 未登录/401/失败静默停上行（不报错、无未处理 rejection）；登出不回滚本地偏好；
 * - 传输走 mobile 既有 apiFetch（C 端会话 + API_BASE 拼接，401 由 client 刷新/清会话）。
 * 回声抑制与 web 同构：拉取覆盖本地后按内容序列化比对跳过随后一次防抖上行。
 */
export function usePreferencesSync(): void {
  const dispatch = useStore((s) => s.dispatch);
  const preferences = useStore((s) => s.state.settings.preferences);
  const isAuthed = useAuthStore((s) => s.session !== null);
  // 会话内首次拉取完成标记（0=进行中）：拉取就绪前不上行，避免登录瞬间本地态回写覆盖后端权威
  const [pullSettled, setPullSettled] = useState(0);
  // 最近一次「来自后端/已对齐」的偏好内容序列化（回声抑制；null=尚无对齐基线）
  const syncedContentRef = useRef<string | null>(null);

  // 登录态变化 → 拉取后端权威偏好（失败静默，本地缓存兜底）
  useEffect(() => {
    if (!isAuthed) {
      setPullSettled(0);
      syncedContentRef.current = null;
      return undefined;
    }
    let alive = true;
    apiFetch<{ messagePush: boolean; themePreference: unknown | null }>('/admin/v1/system/me/preferences')
      .then((view) => {
        if (!alive) return;
        // 契约④：themePreference null=后端未设行 → 本地缓存兜底，不覆盖
        if (view?.themePreference != null) {
          const next = migrateSettings(view.themePreference).settings.preferences;
          // 先记对齐基线再入 store：随后一次 preferences effect 比对相等即跳过上行（回声抑制）
          syncedContentRef.current = serializePreferences(next);
          dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: next } });
        }
      })
      .catch(() => undefined) // 拉取失败（含 401 清会话）：本地缓存兜底，静默
      .finally(() => {
        if (alive) setPullSettled((n) => n + 1);
      });
    return () => {
      alive = false;
    };
  }, [isAuthed, dispatch]);

  // 偏好变更 → 防抖 800ms PUT themePreference（仅 themePreference 域，messagePush 不触碰）
  useEffect(() => {
    if (!isAuthed || pullSettled === 0) {
      return undefined;
    }
    const content = serializePreferences(preferences);
    if (content === syncedContentRef.current) {
      return undefined;
    }
    const timer = setTimeout(() => {
      // 防抖窗口内登出/401 清会话 → 静默停上行（契约⑨）
      if (useAuthStore.getState().session === null) {
        return;
      }
      syncedContentRef.current = content;
      void apiJson<void>(
        '/admin/v1/system/me/preferences',
        'PUT',
        { themePreference: buildThemePreferenceBlob() },
      ).catch(() => undefined); // 上行失败静默（后 PUT 胜口径；本地单 blob 即时生效不受影响）
    }, PREFERENCES_PUT_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [preferences, isAuthed, pullSettled]);
}
