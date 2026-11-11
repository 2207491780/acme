import { useEffect, useState } from 'react';
import type { ComponentType } from 'react';
import { SafeAreaView, StatusBar, View, Text, Pressable, Platform, StyleSheet } from 'react-native';
import { ACTIVE_STATUSES } from '@acme/core';
import { useStore } from './src/store/useStore';
import { useAccent } from './src/hooks';
import { palette, onAccent } from './src/theme';
import type { TabKey } from './src/nav';
import { DashboardScreen } from './src/screens/DashboardScreen';
import { ReleasesScreen } from './src/screens/ReleasesScreen';
import { AppsScreen } from './src/screens/AppsScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { BrandMark, IconDashboard, IconReleases, IconApps, IconSettings } from './src/components/icons';

const TABS: { key: TabKey; label: string; Icon: ComponentType<{ color: string; size?: number }> }[] = [
  { key: 'dashboard', label: '总览', Icon: IconDashboard },
  { key: 'releases', label: '发布', Icon: IconReleases },
  { key: 'apps', label: '应用', Icon: IconApps },
  { key: 'settings', label: '设置', Icon: IconSettings },
];

export default function App() {
  const [tab, setTab] = useState<TabKey>('dashboard');
  const dispatch = useStore((s) => s.dispatch);
  const motion = useStore((s) => s.state.settings.motion);
  const activeCount = useStore(
    (s) => s.state.releases.filter((r) => ACTIVE_STATUSES.includes(r.status)).length,
  );
  const accent = useAccent();

  useEffect(() => {
    if (!motion) return;
    const timer = setInterval(() => dispatch({ type: 'TICK' }), 1200);
    return () => clearInterval(timer);
  }, [dispatch, motion]);

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={palette.bg} />
      <View style={styles.header}>
        <BrandMark color={accent} />
        <View style={{ flex: 1 }}>
          <Text style={styles.brand}>ACME 交付台</Text>
          <Text style={styles.brandSub}>FRONTEND OPS · MOBILE</Text>
        </View>
        <View style={[styles.pill, { borderColor: `${accent}59`, backgroundColor: `${accent}1f` }]}>
          <Text style={[styles.pillText, { color: accent }]}>移动端</Text>
        </View>
      </View>

      <View style={{ flex: 1 }}>
        {tab === 'dashboard' ? <DashboardScreen go={setTab} /> : null}
        {tab === 'releases' ? <ReleasesScreen /> : null}
        {tab === 'apps' ? <AppsScreen go={setTab} /> : null}
        {tab === 'settings' ? <SettingsScreen /> : null}
      </View>

      <View style={styles.tabbar}>
        {TABS.map(({ key, label, Icon }) => {
          const active = tab === key;
          const color = active ? accent : palette.muted;
          return (
            <Pressable key={key} onPress={() => setTab(key)} style={styles.tab}>
              <View style={[styles.tabIconWrap, active ? { backgroundColor: `${accent}1f` } : null]}>
                <Icon color={color} size={22} />
                {key === 'releases' && activeCount > 0 ? (
                  <View style={[styles.badge, { backgroundColor: accent }]}>
                    <Text style={styles.badgeText}>{activeCount}</Text>
                  </View>
                ) : null}
              </View>
              <Text style={[styles.tabLabel, { color }]}>{label}</Text>
            </Pressable>
          );
        })}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: palette.bg,
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight : 0,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
  },
  brand: { color: palette.text, fontSize: 18, fontWeight: '800' },
  brandSub: { color: palette.muted, fontSize: 10, letterSpacing: 2, marginTop: 2 },
  pill: { borderWidth: 1, borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  pillText: { fontSize: 11, fontWeight: '700' },
  tabbar: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: palette.border,
    backgroundColor: palette.surface,
    paddingVertical: 8,
    paddingBottom: 10,
  },
  tab: { flex: 1, alignItems: 'center', gap: 4 },
  tabIconWrap: { width: 46, height: 30, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  badge: {
    position: 'absolute',
    top: -4,
    right: -2,
    minWidth: 16,
    height: 16,
    borderRadius: 99,
    paddingHorizontal: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: { color: onAccent, fontSize: 10, fontWeight: '800' },
  tabLabel: { fontSize: 11, fontWeight: '600' },
});