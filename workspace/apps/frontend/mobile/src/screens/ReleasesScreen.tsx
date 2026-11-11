import { useMemo, useState } from 'react';
import { View, Text, TextInput, FlatList, Pressable, StyleSheet } from 'react-native';
import {
  filterReleases,
  sortReleases,
  relTime,
  statusLabel,
  statusTone,
  platformShort,
  envLabel,
  NEXT_STATUS,
  STATUS_ACTION,
  SORTS,
  type Env,
  type Platform,
  type Query,
  type Release,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { useAccent } from '../hooks';
import { Card, Tag, Button, ProgressBar, Muted } from '../components/ui';
import { TONE_COLOR, PLATFORM_COLOR, ENV_COLOR, palette } from '../theme';

const STATUS_FILTERS: [Query['status'], string][] = [
  ['all', '全部'],
  ['active', '进行中'],
  ['approved', '待发布'],
  ['published', '已发布'],
  ['failed', '失败'],
];
const PLATFORM_FILTERS: [Platform | 'all', string][] = [
  ['all', '全部端'],
  ['web', 'Web'],
  ['desktop', '桌面'],
  ['mobile', '移动'],
];
const ENV_FILTERS: [Env | 'all', string][] = [
  ['all', '全部环境'],
  ['dev', '开发'],
  ['test', '测试'],
  ['prod', '生产'],
];

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  const accent = useAccent();
  return (
    <Pressable
      onPress={onPress}
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
}: {
  release: Release;
  expanded: boolean;
  onToggle: () => void;
  onAdvance: (id: string) => void;
}) {
  const accent = useAccent();
  const tone = TONE_COLOR[statusTone(release.status)];
  const next = NEXT_STATUS[release.status];
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
          <Tag label={platformShort(release.platform)} color={PLATFORM_COLOR[release.platform]} />
          <Tag label={envLabel(release.env)} color={ENV_COLOR[release.env]} />
          <Tag label={statusLabel(release.status)} color={tone} />
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
              <Text style={styles.detailLabel}>产物</Text>
              {release.artifacts.map((a) => (
                <View key={a.name} style={styles.artifactRow}>
                  <Text style={styles.artifactName}>{a.name}</Text>
                  <Muted style={{ fontSize: 12 }}>{a.size}</Muted>
                </View>
              ))}
            </View>
          ) : null}
          <View style={{ gap: 6 }}>
            <Text style={styles.detailLabel}>时间线</Text>
            {release.timeline.slice(-4).map((t, i) => (
              <View key={i} style={styles.timelineRow}>
                <View style={[styles.dot, { backgroundColor: accent }]} />
                <View style={{ flex: 1 }}>
                  <Text style={styles.timelineText}>
                    {t.action} · {t.by}
                  </Text>
                  <Muted style={{ fontSize: 11 }}>{relTime(t.at)}</Muted>
                </View>
              </View>
            ))}
          </View>
        </View>
      ) : null}

      <View style={styles.relFoot}>
        <Muted style={{ fontSize: 11 }}>{relTime(release.updatedAt)}更新</Muted>
        {next ? (
          <Button small label={STATUS_ACTION[release.status] || '推进'} onPress={() => onAdvance(release.id)} />
        ) : (
          <Tag label="已完成" color={TONE_COLOR.success} />
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
        <Text style={styles.screenTitle}>发布单</Text>
        <Muted>{releases.length} 个结果</Muted>
      </View>
      <View style={styles.searchBox}>
        <TextInput
          value={query.search}
          onChangeText={(t) => setQuery({ search: t })}
          placeholder="搜索标题 / 版本 / 负责人 / 应用"
          placeholderTextColor={palette.muted}
          style={styles.searchInput}
        />
        {query.search ? (
          <Pressable
            accessibilityLabel="清空搜索"
            accessibilityRole="button"
            onPress={() => setQuery({ search: '' })}
            hitSlop={12}
          >
            <Text style={{ color: palette.muted, fontSize: 16 }}>✕</Text>
          </Pressable>
        ) : null}
      </View>
      <FlatList
        horizontal
        data={STATUS_FILTERS}
        keyExtractor={(x) => x[0]}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: 8 }}
        renderItem={({ item }) => (
          <Chip label={item[1]} active={query.status === item[0]} onPress={() => setQuery({ status: item[0] })} />
        )}
      />
      <FlatList
        horizontal
        data={PLATFORM_FILTERS}
        keyExtractor={(x) => x[0]}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: 8 }}
        renderItem={({ item }) => (
          <Chip label={item[1]} active={query.platform === item[0]} onPress={() => setQuery({ platform: item[0] })} />
        )}
      />
      <View style={styles.toolbar}>
        <FlatList
          horizontal
          data={ENV_FILTERS}
          keyExtractor={(x) => x[0]}
          showsHorizontalScrollIndicator={false}
          style={{ flex: 1 }}
          contentContainerStyle={{ gap: 8 }}
          renderItem={({ item }) => (
            <Chip label={item[1]} active={query.env === item[0]} onPress={() => setQuery({ env: item[0] })} />
          )}
        />
      </View>
      <View style={styles.headRow}>
        <Chip label={`排序 · ${SORTS[sortIndex][1]}`} active={query.sort !== 'manual'} onPress={cycleSort} />
        <Chip label="重置筛选" active={false} onPress={resetQuery} />
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
        />
      )}
      ListEmptyComponent={
        <Card style={{ marginTop: 12, borderColor: `${accent}33` }}>
          <Muted>没有匹配的发布单，试试调整筛选或重置。</Muted>
        </Card>
      }
    />
  );
}

const styles = StyleSheet.create({
  list: { flex: 1, backgroundColor: palette.bg },
  headRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  screenTitle: { color: palette.text, fontSize: 22, fontWeight: '800' },
  searchBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: palette.surface,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 4,
  },
  searchInput: { flex: 1, color: palette.text, fontSize: 14, paddingVertical: 8 },
  toolbar: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  chip: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 999,
    paddingHorizontal: 14,
    paddingVertical: 7,
    backgroundColor: palette.surface,
  },
  chipText: { fontSize: 12, fontWeight: '600' },
  relHead: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  relTitle: { flex: 1, color: palette.text, fontSize: 16, fontWeight: '700' },
  priority: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 2 },
  priorityText: { fontSize: 12, fontWeight: '800' },
  tagRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 },
  progressRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 12 },
  detail: { gap: 12, paddingTop: 12, marginTop: 4, borderTopWidth: 1, borderTopColor: palette.border },
  detailLabel: { color: palette.text2, fontSize: 12, fontWeight: '700' },
  artifactRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  artifactName: { color: palette.text, fontSize: 13 },
  timelineRow: { flexDirection: 'row', gap: 10, alignItems: 'flex-start' },
  timelineText: { color: palette.text, fontSize: 13 },
  dot: { width: 8, height: 8, borderRadius: 99, marginTop: 5 },
  relFoot: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    marginTop: 4,
  },
});