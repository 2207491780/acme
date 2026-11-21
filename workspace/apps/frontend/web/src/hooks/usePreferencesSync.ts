import { useEffect, useRef, useState } from 'react';
import { migrateSettings, PREFERENCES_VERSION, systemApi, useAuthStore } from '@acme/core';
import { useStore } from '../store/useStore';

/** 偏好上行防抖毫秒数（REQ-20261121-200415 契约⑥钉死：800ms）。 */
const PREFERENCES_PUT_DEBOUNCE_MS = 800;

/**
 * 偏好后端持久化接线（REQ-20261121-200415 契约⑥，AC-7/AC-8）：
 * - 登录成功（含冷启动恢复会话）→ GET preferences：themePreference 非 null 经 migrateSettings
 *   覆盖本地 preferences（后端权威）；null=后端未设 → 本地缓存兜底（契约④不内置默认值）；
 * - 偏好变更即时生效（UPDATE_SETTINGS 即 store 即时持久化，UI 同帧刷新）+ 防抖 800ms 单次
 *   PUT themePreference（按域 upsert，messagePush 域不触碰）；
 * - 401/未登录静默停上行（不发请求、不报错、无未处理 rejection）；登出不回滚本地偏好；
 * - 多窗口=后 PUT 胜（契约⑥，不引入时间戳合并）。
 * 回声抑制：拉取覆盖本地后，以内容序列化比对跳过随后一次防抖上行（StrictMode 双挂载安全）。
 * 注意：本 hook 内含 hooks，必须在 App 条件 return（if (!session)）之前调用。
 */
export function usePreferencesSync(): void {
  const dispatch = useStore((s) => s.dispatch);
  const preferences = useStore((s) => s.state.settings.preferences);
  const isAuthed = useAuthStore((s) => s.session !== null);
  // 会话内首次拉取完成标记（0=进行中）：拉取就绪前不上行，避免登录瞬间本地态回写覆盖后端权威
  const [pullSettled, setPullSettled] = useState(0);
  // 最近一次「来自后端/已对齐」的偏好内容序列化（回声抑制；null=尚无对齐基线）
  const syncedContentRef = useRef<string | null>(null);

  // 登录态变化 → 拉取后端权威偏好（过期响应经 session 序号丢弃；失败静默本地兜底）
  useEffect(() => {
    if (!isAuthed) {
      setPullSettled(0);
      syncedContentRef.current = null;
      return undefined;
    }
    let alive = true;
    systemApi
      .mePreferences()
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
      // 防抖窗口内登出/401 清会话 → 静默停上行（契约⑥）
      if (useAuthStore.getState().session === null) {
        return;
      }
      syncedContentRef.current = content;
      void systemApi
        .mePreferencesUpdate({
          themePreference: { schemaVersion: PREFERENCES_VERSION, preferences },
        })
        .catch(() => undefined); // 上行失败静默（后 PUT 胜口径；本地即时生效不受影响）
    }, PREFERENCES_PUT_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [preferences, isAuthed, pullSettled]);
}

function serializePreferences(preferences: unknown): string {
  return JSON.stringify(preferences);
}
