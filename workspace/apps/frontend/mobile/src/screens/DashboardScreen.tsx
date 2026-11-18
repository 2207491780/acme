import { useCallback, useMemo } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import {
  metrics,
  sortReleases,
  relTime,
  statusTone,
  type NeutralPalette,
  type Platform,
  type Status,
} from '@acme/core';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { Card, Screen, Title, Eyebrow, Muted, Tag } from '../components/ui';
import { PlatformBadge } from '../components/icons';
import { TONE_COLOR, HEALTH_COLOR } from '../theme';
import type { MainTabParamList, TabKey } from '../nav';

function StatTile({ value, label, color, styles }: { value: number; label: string; color: string; styles: DashStyles }) {
  return (
    <View style={[styles.stat, { borderColor: `${color}33` }]}>
      <Text style={[styles.statN, { color }]}>{value}</Text>
      <Text style={styles.statL}>{label}</Text>
    </View>
  );
}

type DashStyles = ReturnType<typeof makeStyles>;

export function DashboardScreen() {
  const state = useStore((s) => s.state);
  const setQuery = useStore((s) => s.setQuery);
  const accent = useAccent();
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const styles = useMemo(() => makeStyles(palette), [palette]);
  const navigation = useNavigation<BottomTabNavigationProp<MainTabParamList>>();
  const go = useCallback((tab: TabKey) => navigation.navigate(tab), [navigation]);
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
        <Eyebrow>{t('dashboard.hero.eyebrow')}</Eyebrow>
        <Title>{t('dashboard.hero.title')}</Title>
        <Muted>{t('dashboard.mobile.heroDesc')}</Muted>
      </View>

      <View style={styles.statGrid}>
        <StatTile value={m.active} label={t('dashboard.stats.active')} color={TONE_COLOR.info} styles={styles} />
        <StatTile value={m.approved} label={t('dashboard.stats.approved')} color={TONE_COLOR.warning} styles={styles} />
        <StatTile value={m.publishedToday} label={t('dashboard.stats.publishedToday')} color={TONE_COLOR.success} styles={styles} />
        <StatTile value={m.failed} label={t('dashboard.stats.failed')} color={TONE_COLOR.danger} styles={styles} />
      </View>

      <Card>
        <View style={styles.cardHead}>
          <Text style={styles.cardTitle}>{t('dashboard.cards.pipeline')}</Text>
          <Muted>{t('dashboard.cards.pipelineTotal', { total: m.total })}</Muted>
        </View>
        <View style={styles.pipeline}>
          {STAGE_ITEMS.map(([key, labelKey]) => (
            <Pressable
              key={key}
              onPress={() => goStatus(key)}
              style={({ pressed }) => [styles.stage, pressed && { opacity: 0.6 }]}
            >
              <Text style={[styles.stageN, { color: TONE_COLOR[statusTone(key)] }]}>{m.byStatus[key] || 0}</Text>
              <Text style={styles.stageL}>{t(labelKey)}</Text>
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
                  <Tag label={t(`releases.health.${app.health}`)} color={HEALTH_COLOR[app.health]} />
                  <Tag label={`v${app.version}`} color={accent} />
                  {latest ? (
                    <Tag label={t(`releases.status.${latest.status}`)} color={TONE_COLOR[statusTone(latest.status)]} />
                  ) : (
                    <Muted>{t('dashboard.noRelease')}</Muted>
                  )}
                </View>
              </Card>
            )}
          </Pressable>
        );
      })}

      <Card>
        <Text style={styles.cardTitle}>{t('dashboard.mobile.recentActivity')}</Text>
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

// 交付流水线五段（标签键与 core dashboard.stages 域一致，运行时经 t() 取文案；顺序保留既有五段）。
const STAGE_ITEMS: readonly [Status, string][] = [
  ['draft', 'dashboard.stages.draft'],
  ['building', 'dashboard.stages.building'],
  ['testing', 'dashboard.stages.testing'],
  ['approved', 'dashboard.stages.approved'],
  ['published', 'dashboard.stages.published'],
];

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    statGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
    stat: {
      flexBasis: '45%',
      flexGrow: 1,
      backgroundColor: p.elevated,
      borderRadius: 14,
      borderWidth: 1,
      padding: 14,
      gap: 2,
    },
    statN: { fontSize: 30, fontWeight: '800' },
    statL: { color: p.text2, fontSize: 12 },
    cardHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    cardTitle: { color: p.text, fontSize: 16, fontWeight: '700' },
    pipeline: { flexDirection: 'row', gap: 8 },
    stage: {
      flex: 1,
      backgroundColor: p.elevated,
      borderRadius: 12,
      paddingVertical: 12,
      alignItems: 'center',
      gap: 4,
    },
    stageN: { fontSize: 20, fontWeight: '800' },
    stageL: { color: p.text2, fontSize: 11 },
    appHead: { flexDirection: 'row', alignItems: 'center', gap: 12 },
    appName: { color: p.text, fontSize: 16, fontWeight: '700' },
    tagRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
    activityRow: { flexDirection: 'row', gap: 10, alignItems: 'flex-start' },
    dot: { width: 9, height: 9, borderRadius: 99, marginTop: 5 },
    activityText: { color: p.text, fontSize: 13 },
  });
