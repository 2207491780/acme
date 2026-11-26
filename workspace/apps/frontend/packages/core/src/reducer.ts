import { seedState } from './seed';
import { parseState, isAppPatch } from './parse-state';
import {
  buildSettings,
  isAccentKey,
  isDensityValue,
  validateThemePreferences,
} from './preferences';
import { STORAGE_KEY } from './constants';
import type {
  Action,
  AppState,
  Settings,
  StorageLike,
} from './types';

export function loadState(storage: StorageLike, now: number = Date.now()): AppState {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    return (raw ? parseState(JSON.parse(raw), now) : null) ?? seedState(now);
  } catch {
    return seedState(now);
  }
}

/** 返回持久化是否成功，存储不可用时保留内存态。 */
export function saveState(storage: StorageLike, state: AppState): boolean {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(state));
    return true;
  } catch {
    return false;
  }
}

export function reduce(
  state: AppState,
  action: Action,
  _now: number = Date.now(),
): AppState {
  const s = structuredClone(state);
  switch (action.type) {
    case 'UPDATE_SETTINGS': {
      // preferences 为唯一事实源（REQ-20261121-010725）：preferences 子对象整体合并后统一
      // 经 validateThemePreferences 归一化并由 buildSettings 重建（版本标记不受补丁改写）。
      // legacy 扁平补丁「写 preferences」语义不变（THEME-3 契约⑧）：theme/dark→mode、
      // accent/density/motion 同名映射；显式选择预设 accent 即放弃自定义取色。
      const patch = action.patch as Partial<Settings>;
      const legacy = patch as Record<string, unknown>;
      const prefs = { ...s.settings.preferences, ...patch.preferences };
      if (legacy.theme === 'dark' || legacy.theme === 'light') prefs.mode = legacy.theme;
      else if (typeof legacy.dark === 'boolean') prefs.mode = legacy.dark ? 'dark' : 'light';
      if (isAccentKey(legacy.accent)) {
        prefs.accent = legacy.accent;
        prefs.customAccent = null;
      }
      if (isDensityValue(legacy.density)) prefs.density = legacy.density;
      if (typeof legacy.motion === 'boolean') prefs.motionEnabled = legacy.motion;
      s.settings = buildSettings(validateThemePreferences(prefs));
      return s;
    }
    case 'UPDATE_APP': {
      const app = s.apps.find((a) => a.id === action.id);
      if (!app) return state;
      // patch 运行时校验（review 修复）：字段白名单 + 枚举校验，与 IMPORT 同一标准
      if (!isAppPatch(action.patch)) return state;
      Object.assign(app, action.patch);
      return s;
    }
    case 'IMPORT': {
      const next = parseState(action.data, _now);
      return next ?? state;
    }
    case 'RESET': {
      return seedState(_now);
    }
    default:
      return state;
  }
}
