import { useStore } from './store/useStore';
import { ACCENTS, ON_ACCENT } from './theme';

export function useAccent(): string {
  const accent = useStore((s) => s.state.settings.accent);
  return ACCENTS[accent];
}

export function useOnAccent(): string {
  const accent = useStore((s) => s.state.settings.accent);
  return ON_ACCENT[accent];
}