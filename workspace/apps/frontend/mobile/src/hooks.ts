import { useStore } from './store/useStore';
import { resolveAccentColor, resolveOnAccent } from '@acme/core';

// 强调色读取点（THEME-3 契约⑧）：脱离旧扁平投影字段，改走 preferences 唯一事实源
// （resolveAccentColor：customAccent 非 null 时优先，色板/取色同步生效，契约⑦）。

export function useAccent(): string {
  return useStore((s) => resolveAccentColor(s.state.settings.preferences));
}

export function useOnAccent(): string {
  return useStore((s) => resolveOnAccent(s.state.settings.preferences));
}
