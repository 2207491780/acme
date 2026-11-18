import { View, Text, StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import { platformLabel, type Platform } from '@acme/core';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { Card, Screen, Title, Eyebrow, Muted, Tag, Button } from '../components/ui';
import { PlatformBadge } from '../components/icons';
import { HEALTH_COLOR, HEALTH_LABEL, palette } from '../theme';
import type { ScreenProps } from '../nav';

function InfoRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <View style={styles.infoRow}>
      <Muted>{label}</Muted>
      {children}
    </View>
  );
}

const REUSE: { platform: Platform; title: string; desc: string; bridge?: string }[] = [
  {
    platform: 'web',
    title: 'Web 端',
    desc: 'React + Vite + Ant Design · 页面与业务逻辑唯一来源',
    bridge: '↓ 开发加载 Dev Server · 打包加载构建输出',
  },
  {
    platform: 'desktop',
    title: 'Electron 桌面端',
    desc: '主进程 / 预加载桥接 · 复用 Web 产物',
    bridge: '↓ 共享 @acme/core 业务层',
  },
  { platform: 'mobile', title: '移动端', desc: 'React Native + Expo · 共享 core 业务层' },
];

export function AppsScreen({ go }: ScreenProps) {
  const apps = useStore((s) => s.state.apps);
  const releases = useStore((s) => s.state.releases);
  const setQuery = useStore((s) => s.setQuery);
  const accent = useAccent();

  const viewReleases = (platform: Platform) => {
    setQuery({ platform, status: 'all', env: 'all', search: '' });
    go('releases');
  };

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <Eyebrow>Applications</Eyebrow>
        <Title>应用与工程结构</Title>
        <Muted>
          pnpm monorepo：共享 core 业务层，Web 为页面唯一来源，Electron 复用 Web 产物，React Native 共享 core。
        </Muted>
      </View>

      {apps.map((app) => {
        const count = releases.filter((r) => r.appId === app.id).length;
        return (
          <Card key={app.id}>
            <View style={styles.appHead}>
              <PlatformBadge platform={app.platform} />
              <View style={{ flex: 1 }}>
                <Text style={styles.appName}>{app.name}</Text>
                <Muted style={{ fontSize: 12 }}>{app.path}</Muted>
              </View>
            </View>
            <Muted>{app.description}</Muted>
            <View style={styles.infoBox}>
              <InfoRow label="当前版本">
                <Text style={[styles.infoValue, { color: accent }]}>v{app.version}</Text>
              </InfoRow>
              <InfoRow label="负责人">
                <Text style={styles.infoValue}>{app.owner}</Text>
              </InfoRow>
              <InfoRow label="健康度">
                <Tag label={HEALTH_LABEL[app.health]} color={HEALTH_COLOR[app.health]} />
              </InfoRow>
              <InfoRow label="发布单">
                <Text style={styles.infoValue}>{count} 个</Text>
              </InfoRow>
            </View>
            <Button variant="ghost" label={`查看 ${platformLabel(app.platform)} 发布`} onPress={() => viewReleases(app.platform)} />
          </Card>
        );
      })}

      <Card>
        <Text style={styles.cardTitle}>复用关系</Text>
        <View style={{ gap: 10 }}>
          {REUSE.map((r) => (
            <View key={r.platform} style={{ gap: 8 }}>
              <View style={styles.reuseRow}>
                <PlatformBadge platform={r.platform} size={34} />
                <View style={{ flex: 1 }}>
                  <Text style={styles.appName}>{r.title}</Text>
                  <Muted style={{ fontSize: 12 }}>{r.desc}</Muted>
                </View>
              </View>
              {r.bridge ? <Muted style={{ fontSize: 12, paddingLeft: 46 }}>{r.bridge}</Muted> : null}
            </View>
          ))}
        </View>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  appHead: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  appName: { color: palette.text, fontSize: 16, fontWeight: '700' },
  cardTitle: { color: palette.text, fontSize: 16, fontWeight: '700' },
  infoBox: { gap: 10, paddingVertical: 4 },
  infoRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  infoValue: { color: palette.text, fontSize: 14, fontWeight: '600' },
  reuseRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
});