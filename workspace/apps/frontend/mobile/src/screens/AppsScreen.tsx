import { useMemo } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import {
  type NeutralPalette,
  type Platform,
} from '@acme/core';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { Card, Screen, Title, Eyebrow, Muted, Tag, Button } from '../components/ui';
import { PlatformBadge } from '../components/icons';
import { HEALTH_COLOR } from '../theme';
import type { MainTabParamList, TabKey } from '../nav';

type AppStyles = ReturnType<typeof makeStyles>;

function InfoRow({ label, children, styles }: { label: string; children: ReactNode; styles: AppStyles }) {
  return (
    <View style={styles.infoRow}>
      <Muted>{label}</Muted>
      {children}
    </View>
  );
}

// 复用关系（文案键 = core apps.reuse 域；描述/桥接与 Web 同源）。
const REUSE_KEYS: { platform: Platform; titleKey: string; descKey: string; bridgeKey?: string }[] = [
  { platform: 'web', titleKey: 'apps.reuse.web', descKey: 'apps.reuse.webDesc', bridgeKey: 'apps.reuse.webArrow' },
  { platform: 'desktop', titleKey: 'apps.reuse.desktop', descKey: 'apps.reuse.desktopDesc', bridgeKey: 'apps.reuse.coreArrow' },
  { platform: 'mobile', titleKey: 'apps.reuse.mobile', descKey: 'apps.reuse.mobileDesc' },
];

export function AppsScreen() {
  const apps = useStore((s) => s.state.apps);
  const releases = useStore((s) => s.state.releases);
  const setQuery = useStore((s) => s.setQuery);
  const accent = useAccent();
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const styles = useMemo(() => makeStyles(palette), [palette]);
  const navigation = useNavigation<BottomTabNavigationProp<MainTabParamList>>();

  const viewReleases = (platform: Platform) => {
    setQuery({ platform, status: 'all', env: 'all', search: '' });
    navigation.navigate('releases' satisfies TabKey);
  };

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <Eyebrow>{t('apps.head.eyebrow')}</Eyebrow>
        <Title>{t('apps.head.title')}</Title>
        <Muted>{t('apps.head.sub')}</Muted>
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
              <InfoRow label={t('apps.card.currentVersion')} styles={styles}>
                <Text style={[styles.infoValue, { color: accent }]}>v{app.version}</Text>
              </InfoRow>
              <InfoRow label={t('apps.card.owner')} styles={styles}>
                <Text style={styles.infoValue}>{app.owner}</Text>
              </InfoRow>
              <InfoRow label={t('apps.card.health')} styles={styles}>
                <Tag label={t(`releases.health.${app.health}`)} color={HEALTH_COLOR[app.health]} />
              </InfoRow>
              <InfoRow label={t('apps.card.releases')} styles={styles}>
                <Text style={styles.infoValue}>{t('apps.card.releaseCount', { n: count })}</Text>
              </InfoRow>
            </View>
            <Button
              variant="ghost"
              label={t('apps.mobile.viewReleases', { platform: t(`releases.platformFull.${app.platform}`) })}
              onPress={() => viewReleases(app.platform)}
            />
          </Card>
        );
      })}

      <Card>
        <Text style={styles.cardTitle}>{t('apps.reuse.title')}</Text>
        <View style={{ gap: 10 }}>
          {REUSE_KEYS.map((r) => (
            <View key={r.platform} style={{ gap: 8 }}>
              <View style={styles.reuseRow}>
                <PlatformBadge platform={r.platform} size={34} />
                <View style={{ flex: 1 }}>
                  <Text style={styles.appName}>{t(r.titleKey)}</Text>
                  <Muted style={{ fontSize: 12 }}>{t(r.descKey)}</Muted>
                </View>
              </View>
              {r.bridgeKey ? <Muted style={{ fontSize: 12, paddingLeft: 46 }}>{t(r.bridgeKey)}</Muted> : null}
            </View>
          ))}
        </View>
      </Card>
    </Screen>
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    appHead: { flexDirection: 'row', alignItems: 'center', gap: 12 },
    appName: { color: p.text, fontSize: 16, fontWeight: '700' },
    cardTitle: { color: p.text, fontSize: 16, fontWeight: '700' },
    infoBox: { gap: 10, paddingVertical: 4 },
    infoRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    infoValue: { color: p.text, fontSize: 14, fontWeight: '600' },
    reuseRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  });
