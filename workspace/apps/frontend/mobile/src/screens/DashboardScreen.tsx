import { useMemo } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import {
  type NeutralPalette,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { Card, Screen, Title, Eyebrow, Muted, Tag } from '../components/ui';
import { PlatformBadge } from '../components/icons';
import { HEALTH_COLOR } from '../theme';

/** 概览屏（REQ-20261126-125836）：保留多端基座（三端清单/健康度/版本），发布指标、流水线与动态流随演示域删除。 */
export function DashboardScreen() {
  const apps = useStore((s) => s.state.apps);
  const accent = useAccent();
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const styles = useMemo(() => makeStyles(palette), [palette]);

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <Eyebrow>{t('dashboard.hero.eyebrow')}</Eyebrow>
        <Title>{t('dashboard.hero.title')}</Title>
        <Muted>{t('dashboard.mobile.heroDesc')}</Muted>
      </View>

      {apps.map((app) => (
        <Card key={app.id}>
          <View style={styles.appHead}>
            <PlatformBadge platform={app.platform} />
            <View style={{ flex: 1 }}>
              <Text style={styles.appName}>{app.name}</Text>
              <Muted style={{ fontSize: 12 }}>{app.path}</Muted>
            </View>
          </View>
          <Muted>{app.description}</Muted>
          <View style={styles.tagRow}>
            <Tag label={t(`common.health.${app.health}`)} color={HEALTH_COLOR[app.health]} />
            <Tag label={`v${app.version}`} color={accent} />
          </View>
        </Card>
      ))}
    </Screen>
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    appHead: { flexDirection: 'row', alignItems: 'center', gap: 12 },
    appName: { color: p.text, fontSize: 16, fontWeight: '700' },
    tagRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
  });
