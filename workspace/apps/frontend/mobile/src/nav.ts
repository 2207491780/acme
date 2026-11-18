export type TabKey = 'dashboard' | 'releases' | 'apps' | 'settings';

export interface ScreenProps {
  go: (tab: TabKey) => void;
}