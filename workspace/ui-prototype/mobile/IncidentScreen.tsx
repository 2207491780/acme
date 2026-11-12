import { useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { fmtFull, incidents, type Incident } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const SEVERITY_COLOR: Record<Incident['severity'], string> = { P1: TONE_COLOR.danger, P2: TONE_COLOR.warning, P3: TONE_COLOR.muted };
const FILTERS = ['all', 'open', 'resolved', 'P1'] as const;

/** S1 事故与事件流（APP 形态）：失败单 → 事故卡 + MTTR 统计 + 点选详情。 */
export default function IncidentScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const data = useMemo(() => incidents(state, Date.now()), [state]);
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const filtered = data.list.filter((x) =>
    filter === 'all' ? true : filter === 'open' ? !x.resolved : filter === 'resolved' ? x.resolved : x.severity === 'P1',
  );
  const selected = data.list.find((x) => x.id === selectedId);

  return (
    <ProtoShell
      title="事故与事件流"
      domain="稳定性域 S1"
      desc="失败发布单自动转事故单；MTTR 与回滚就绪度一览。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="事故总数" value={data.list.length} tone="danger" />
        <Kpi label="未关闭" value={data.open} tone="warning" />
        <Kpi label="平均 MTTR" value={`${data.mttrAvgHours}h`} tone="info" />
        <Kpi label="回滚就绪" value={data.list.filter((x) => x.rollbackReady).length} tone="success" />
      </KpiRow>

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        {FILTERS.map((f) => (
          <Chip key={f} label={{ all: '全部', open: '未关闭', resolved: '已关闭', P1: 'P1' }[f]} active={filter === f} onPress={() => setFilter(f)} />
        ))}
      </View>

      <Section title={`事故（${filtered.length}）`}>
        {filtered.length === 0 ? (
          <Muted>没有匹配的事故单。</Muted>
        ) : (
          filtered.map((x) => (
            <Pressable
              key={x.id}
              accessibilityRole="button"
              accessibilityLabel={`事故 ${x.id}，${x.resolved ? '已关闭' : '处理中'}`}
              onPress={() => setSelectedId(x.id === selectedId ? null : x.id)}
              style={[styles.row, selectedId === x.id && { borderColor: TONE_COLOR.info }]}
            >
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 4 }}>
                <Tag label={x.severity} color={SEVERITY_COLOR[x.severity]} />
                <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{x.id}</Text>
                <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>{x.title}</Text>
              </View>
              <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <MonoText>{x.releaseId} · {x.env}</MonoText>
                <Tag label={x.resolved ? `已关闭 ${x.mttrHours}h` : '处理中'} color={x.resolved ? TONE_COLOR.success : TONE_COLOR.info} />
                {x.rollbackReady ? <Tag label="回滚就绪" color={TONE_COLOR.success} /> : null}
              </View>
            </Pressable>
          ))
        )}
      </Section>

      {selected ? (
        <Section title={`事故详情 · ${selected.id}`}>
          <MonoText color={palette.text}>发布单：{selected.releaseId}（{selected.title}）</MonoText>
          <MonoText color={palette.text}>发生时间：{fmtFull(selected.openedAt)}</MonoText>
          <MonoText color={palette.text2}>
            处置链路：事故确认 → 失败归因（C1）→ 回滚执行（A5）→ 复盘留痕（C3）。
          </MonoText>
        </Section>
      ) : null}
    </ProtoShell>
  );
}

const styles = {
  row: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    marginBottom: 8,
    backgroundColor: palette.elevated,
    gap: 4,
  },
};
