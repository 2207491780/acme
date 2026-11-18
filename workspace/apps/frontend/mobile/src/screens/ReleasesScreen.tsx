import { useMemo, useState } from 'react';
import { View, Text, TextInput, FlatList, Pressable, StyleSheet } from 'react-native';
import {
  filterReleases,
  sortReleases,
  relTime,
  statusTone,
  NEXT_STATUS,
  STATUS_ACTION,
  SORTS,
  type Env,
  type NeutralPalette,
  type Platform,
  type Query,
  type Release,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { Card, Tag, Button, ProgressBar, Muted } from '../components/ui';
import { TONE_COLOR, PLATFORM_COLOR, ENV_COLOR } from '../theme';

// 筛选/文案键（契约③）：i18n 键为单一事实源（core releases 域 + releases.mobile 组）。
const STATUS_FILTER_KEYS: [Query['status'], string][] = [
  ['all', 'releases.mobile.statusFilters.all'],
  ['active', 'releases.mobile.statusFilters.active'],
  ['approved', 'releases.mobile.statusFilters.approved'],
  ['published', 'releases.mobile.statusFilters.published'],
  ['failed', 'releases.mobile.statusFilters.failed'],
];
const PLATFORM_FILTER_KEYS: [Platform | 'all', string][] = [
  ['all', 'releases.mobile.platformFilters.all'],
  ['web', 'releases.mobile.platformFilters.web'],
  ['desktop', 'releases.mobile.platformFilters.desktop'],
  ['mobile', 'releases.mobile.platformFilters.mobile'],
];
const ENV_FILTER_KEYS: [Env | 'all', string][] = [
  ['all', 'releases.filters.allEnvs'],
  ['dev', 'releases.env.dev'],
  ['test', 'releases.env.test'],
  ['prod', 'releases.env.prod'],
];

type RelStyles = ReturnType<typeof makeStyles>;

function Chip({ label, active, onPress, styles }: { label: string; active: boolean; onPress: () => void; styles: RelStyles }) {
  const accent = useAccent();
  const { palette } = useAppTheme();
  return (
    <Pressable
      onPress={onPress}
      hitSlop={{ top: 8, bottom: 8 }}
      style={[styles.chip, active ? { backgroundColor: `${accent}29`, borderColor: accent } : null]}
    >
      <Text style={[styles.chipText, { color: active ? accent : palette.text2 }]}>{label}</Text>
    </Pressable>
  );
}

function ReleaseCard({
  release,
  expanded,
  onToggle,
  onAdvance,
  styles,
}: {
  release: Release;
  expanded: boolean;
  onToggle: () => void;
  onAdvance: (id: string) => void;
  styles: RelStyles;
}) {
  const accent = useAccent();
  const { t } = useTranslation();
  const tone = TONE_COLOR[statusTone(release.status)];
  return (
    <Card style={expanded ? { borderColor: `${accent}66` } : undefined}>
      <Pressable onPress={onToggle}>
        <View style={styles.relHead}>
          <Text style={styles.relTitle}>{release.title}</Text>
          <View style={[styles.priority, { borderColor: `${accent}59`, backgroundColor: `${accent}1f` }]}>
            <Text style={[styles.priorityText, { color: accent }]}>P{release.priority}</Text>
          </View>
        </View>
        <Muted style={{ fontSize: 12, marginTop: 4 }}>
          {release.id} · v{release.version} · {release.owner}
        </Muted>
        <View style={styles.tagRow}>
          <Tag label={t(`releases.platform.${release.platform}`)} color={PLATFORM_COLOR[release.platform]} />
          <Tag label={t(`releases.env.${release.env}`)} color={ENV_COLOR[release.env]} />
          <Tag label={t(`releases.status.${release.status}`)} color={tone} />
        </View>
        <View style={styles.progressRow}>
          <View style={{ flex: 1 }}>
            <ProgressBar percent={release.progress} color={tone} />
          </View>
          <Muted style={{ fontSize: 11, width: 34, textAlign: 'right' }}>{release.progress}%</Muted>
        </View>
      </Pressable>

      {expanded ? (
        <View style={styles.detail}>
          {release.notes ? <Muted>{release.notes}</Muted> : null}
          {release.artifacts.length ? (
            <View style={{ gap: 4 }}>
              <Text style={styles.detailLabel}>{t('releases.drawer.artifacts')}</Text>
              {release.artifacts.map((a) => (
                <View key={a.name} style={styles.artifactRow}>
                  <Text style={styles.artifactName}>{a.name}</Text>
                  <Muted style={{ fontSize: 12 }}>{a.size}</Muted>
                </View>
              ))}
            </View>
          ) : null}
          <View style={{ gap: 6 }}>
            <Text style={styles.detailLabel}>{t('releases.drawer.timeline')}</Text>
            {release.timeline.slice(-4).map((item, i) => (
              <View key={i} style={styles.timelineRow}>
                <View style={[styles.dot, { backgroundColor: accent }]} />
                <View style={{ flex: 1 }}>
                  <Text style={styles.timelineText}>
                    {item.action} · {item.by}
                  </Text>
                  <Muted style={{ fontSize: 11 }}>{relTime(item.at)}</Muted>
                </View>
              </View>
            ))}
          </View>
        </View>
      ) : null}

      <View style={styles.relFoot}>
        <Muted style={{ fontSize: 11 }}>{t('releases.mobile.updatedAt', { time: relTime(release.updatedAt) })}</Muted>
        {NEXT_STATUS[release.status] ? (
          <Button
            small
            label={STATUS_ACTION[release.status] ?? t('releases.mobile.advance')}
            onPress={() => onAdvance(release.id)}
          />
        ) : (
          <Tag label={t('releases.mobile.completed')} color={TONE_COLOR.success} />
        )}
      </View>
    </Card>
  );
}

export function ReleasesScreen() {
  const state = useStore((s) => s.state);
  const query = useStore((s) => s.query);
  const setQuery = useStore((s) => s.setQuery);
  const resetQuery = useStore((s) => s.resetQuery);
  const advance = useStore((s) => s.advance);
  const accent = useAccent();
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const styles = useMemo(() => makeStyles(palette), [palette]);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const releases = useMemo(
    () => sortReleases(filterReleases(state, query), query.sort, state.order),
    [state, query],
  );

  const sortIndex = Math.max(0, SORTS.findIndex(([k]) => k === query.sort));
  const cycleSort = () => setQuery({ sort: SORTS[(sortIndex + 1) % SORTS.length][0] });

  const header = (
    <View style={{ gap: 12 }}>
      <View style={styles.headRow}>
        <Text style={styles.screenTitle}>{t('releases.head.title')}</Text>
        <Muted>{t('releases.mobile.resultsCount', { count: releases.length })}</Muted>
      </View>
      <View style={styles.searchBox}>
        <TextInput
          value={query.search}
          onChangeText={(v) => setQuery({ search: v })}
          placeholder={t('releases.mobile.searchPlaceholder')}
          placeholderTextColor={palette.muted}
          style={styles.searchInput}
        />
        {query.search ? (
          <Pressable
            accessibilityLabel={t('releases.mobile.clearSearch')}
            accessibilityRole="button"
            onPress={() => setQuery({ search: '' })}
            hitSlop={14}
          >
            <Text style={{ color: palette.muted, fontSize: 16 }}>✕</Text>
          </Pressable>
        ) : null}
      </View>
      <FlatList
        horizontal
        data={STATUS_FILTER_KEYS}
        keyExtractor={(x) => x[0]}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: 8 }}
        renderItem={({ item }) => (
          <Chip
            label={t(item[1])}
            active={query.status === item[0]}
            onPress={() => setQuery({ status: item[0] })}
            styles={styles}
          />
        )}
      />
      <FlatList
        horizontal
        data={PLATFORM_FILTER_KEYS}
        keyExtractor={(x) => x[0]}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: 8 }}
        renderItem={({ item }) => (
          <Chip
            label={t(item[1])}
            active={query.platform === item[0]}
            onPress={() => setQuery({ platform: item[0] })}
            styles={styles}
          />
        )}
      />
      <View style={styles.toolbar}>
        <FlatList
          horizontal
          data={ENV_FILTER_KEYS}
          keyExtractor={(x) => x[0]}
          showsHorizontalScrollIndicator={false}
          style={{ flex: 1 }}
          contentContainerStyle={{ gap: 8 }}
          renderItem={({ item }) => (
            <Chip
              label={t(item[1])}
              active={query.env === item[0]}
              onPress={() => setQuery({ env: item[0] })}
              styles={styles}
            />
          )}
        />
      </View>
      <View style={styles.headRow}>
        <Chip
          label={t('releases.mobile.sortLabel', { label: t(`releases.sort.${query.sort}`) })}
          active={query.sort !== 'manual'}
          onPress={cycleSort}
          styles={styles}
        />
        <Chip label={t('releases.mobile.resetFilters')} active={false} onPress={resetQuery} styles={styles} />
      </View>
    </View>
  );

  return (
    <FlatList
      style={styles.list}
      data={releases}
      keyExtractor={(r) => r.id}
      ListHeaderComponent={header}
      contentContainerStyle={{ padding: 16, paddingBottom: 32, gap: 12 }}
      renderItem={({ item }) => (
        <ReleaseCard
          release={item}
          expanded={expandedId === item.id}
          onToggle={() => setExpandedId((cur) => (cur === item.id ? null : item.id))}
          onAdvance={advance}
          styles={styles}
        />
      )}
      ListEmptyComponent={
        <Card style={{ marginTop: 12, borderColor: `${accent}33` }}>
          <Muted>{t('releases.mobile.empty')}</Muted>
        </Card>
      }
    />
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    list: { flex: 1, backgroundColor: p.bg },
    headRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
    screenTitle: { color: p.text, fontSize: 22, fontWeight: '800' },
    searchBox: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      backgroundColor: p.surface,
      borderWidth: 1,
      borderColor: p.border,
      borderRadius: 12,
      paddingHorizontal: 14,
      paddingVertical: 4,
    },
    searchInput: { flex: 1, color: p.text, fontSize: 14, paddingVertical: 8 },
    toolbar: { flexDirection: 'row', alignItems: 'center', gap: 8 },
    chip: {
      borderWidth: 1,
      borderColor: p.border,
      borderRadius: 999,
      paddingHorizontal: 14,
      paddingVertical: 7,
      backgroundColor: p.surface,
    },
    chipText: { fontSize: 12, fontWeight: '600' },
    relHead: { flexDirection: 'row', alignItems: 'center', gap: 10 },
    relTitle: { flex: 1, color: p.text, fontSize: 16, fontWeight: '700' },
    priority: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 2 },
    priorityText: { fontSize: 12, fontWeight: '800' },
    tagRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 },
    progressRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 12 },
    detail: { gap: 12, paddingTop: 12, marginTop: 4, borderTopWidth: 1, borderTopColor: p.border },
    detailLabel: { color: p.text2, fontSize: 12, fontWeight: '700' },
    artifactRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
    artifactName: { color: p.text, fontSize: 13 },
    timelineRow: { flexDirection: 'row', gap: 10, alignItems: 'flex-start' },
    timelineText: { color: p.text, fontSize: 13 },
    dot: { width: 8, height: 8, borderRadius: 99, marginTop: 5 },
    relFoot: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 10,
      marginTop: 4,
    },
  });
