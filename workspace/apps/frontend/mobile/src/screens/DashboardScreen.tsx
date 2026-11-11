import { View, Text, Pressable, StyleSheet } from 'react-native';
import {
  metrics,
  sortReleases,
  relTime,
  statusLabel,
  statusTone,
  type Platform,
  type Status,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { Card, Screen, Title, Eyebrow, Muted, Tag } from '../components/ui';
import { PlatformBadge } from '../components/icons';
import { TONE_COLOR, HEALTH_COLOR, HEALTH_LABEL, palette } from '../theme';
import type { ScreenProps } from '../nav';

const STAGES: [Status, string][] = [
  ['draft', '草稿'],
  ['building', '构建'],
  ['testing', '测试'],
  ['approved', '审批'],
  ['published', '发布'],
];

function StatTile({ value, label, color }: { value: number; label: string; color: string }) {
  return (
    <View style={[styles.stat, { borderColor: `${color}33` }]}>
      <Text style={[styles.statN, { color }]}>{value}</Text>
      <Text style={styles.statL}>{label}</Text>
    </View>
  );
}

export function DashboardScreen({ go }: ScreenProps) {
  const state = useStore((s) => s.state);
  const setQuery = useStore((s) => s.setQuery);
  const accent = useAccent();
  const m = metrics(state);

  const goPlatform = (platform: Platform) => {
    setQuery({ platform, status: 'all', env: 'all', search: '' });
    go('releases');
  };
  const goStatus = (status: Status) => {
    setQuery({ status, platform: 'all', env: 'all', search: '' });
    go('releases');
  };

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <Eyebrow>ACME · Frontend Delivery</Eyebrow>
        <Title>多端交付，一屏掌控</Title>
        <Muted>
          统一管理 Web、Electron 桌面端与移动端的发布单。移动端共享 @acme/core 业务层，从构建、测试、审批到发布全程可追踪。
        </Muted>
      </View>

      <View style={styles.statGrid}>
        <StatTile value={m.active} label="进行中" color={TONE_COLOR.info} />
        <StatTile value={m.approved} label="待发布" color={TONE_COLOR.warning} />
        <StatTile value={m.publishedToday} label="今日发布" color={TONE_COLOR.success} />
        <StatTile value={m.failed} label="失败待处理" color={TONE_COLOR.danger} />
      </View>

      <Card>
        <View style={styles.cardHead}>
          <Text style={styles.cardTitle}>交付流水线</Text>
          <Muted>共 {m.total} 个发布单</Muted>
        </View>
        <View style={styles.pipeline}>
          {STAGES.map(([key, label]) => (
            <Pressable
              key={key}
              onPress={() => goStatus(key)}
              style={({ pressed }) => [styles.stage, pressed && { opacity: 0.6 }]}
            >
              <Text style={[styles.stageN, { color: TONE_COLOR[statusTone(key)] }]}>{m.byStatus[key] || 0}</Text>
              <Text style={styles.stageL}>{label}</Text>
            </Pressable>
          ))}
        </View>
      </Card>

      {state.apps.map((app) => {
        const latest = sortReleases(
          state.releases.filter((r) => r.appId === app.id),
          'updated',
          state.order,
        )[0];
        return (
          <Pressable key={app.id} onPress={() => goPlatform(app.platform)}>
            {({ pressed }) => (
              <Card style={pressed ? { opacity: 0.7, borderColor: `${accent}66` } : undefined}>
                <View style={styles.appHead}>
                  <PlatformBadge platform={app.platform} />
                  <View style={{ flex: 1 }}>
                    <Text style={styles.appName}>{app.name}</Text>
                    <Muted style={{ fontSize: 12 }}>{app.path}</Muted>
                  </View>
                </View>
                <Muted>{app.description}</Muted>
                <View style={styles.tagRow}>
                  <Tag label={HEALTH_LABEL[app.health]} color={HEALTH_COLOR[app.health]} />
                  <Tag label={`v${app.version}`} color={accent} />
                  {latest ? (
                    <Tag label={statusLabel(latest.status)} color={TONE_COLOR[statusTone(latest.status)]} />
                  ) : (
                    <Muted>暂无发布</Muted>
                  )}
                </View>
              </Card>
            )}
          </Pressable>
        );
      })}

      <Card>
        <Text style={styles.cardTitle}>最近动态</Text>
        <View style={{ gap: 12 }}>
          {state.activity.slice(0, 12).map((a) => (
            <View key={a.id} style={styles.activityRow}>
              <View style={[styles.dot, { backgroundColor: TONE_COLOR[a.tone] }]} />
              <View style={{ flex: 1 }}>
                <Text style={styles.activityText}>{a.text}</Text>
                <Muted style={{ fontSize: 11 }}>{relTime(a.at)}</Muted>
              </View>
            </View>
          ))}
        </View>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  statGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  stat: {
    flexBasis: '45%',
    flexGrow: 1,
    backgroundColor: palette.elevated,
    borderRadius: 14,
    borderWidth: 1,
    padding: 14,
    gap: 2,
  },
  statN: { fontSize: 30, fontWeight: '800' },
  statL: { color: palette.text2, fontSize: 12 },
  cardHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  cardTitle: { color: palette.text, fontSize: 16, fontWeight: '700' },
  pipeline: { flexDirection: 'row', gap: 8 },
  stage: {
    flex: 1,
    backgroundColor: palette.elevated,
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
    gap: 4,
  },
  stageN: { fontSize: 20, fontWeight: '800' },
  stageL: { color: palette.text2, fontSize: 11 },
  appHead: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  appName: { color: palette.text, fontSize: 16, fontWeight: '700' },
  tagRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
  activityRow: { flexDirection: 'row', gap: 10, alignItems: 'flex-start' },
  dot: { width: 9, height: 9, borderRadius: 99, marginTop: 5 },
  activityText: { color: palette.text, fontSize: 13 },
});