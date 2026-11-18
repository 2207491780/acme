import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { buildNodes, NODE_STATUS_META, publishTrend, releaseFunnel, metrics } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, ProgressBar, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const HEAT_BG = [
  'transparent',
  'rgba(111,146,196,0.22)',
  'rgba(111,146,196,0.42)',
  'rgba(111,146,196,0.68)',
  'rgba(111,146,196,1)',
];

/** V0 数据大屏（APP 形态）：KPI / 趋势 / 漏斗 / 热力 / 节点 / 构建进度的紧凑总览。 */
export default function WallScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const m = useMemo(() => metrics(state), [state]);
  const trend = useMemo(() => publishTrend(state, 14, Date.now()), [state]);
  const funnel = useMemo(() => releaseFunnel(state), [state]);
  const nodes = useMemo(() => buildNodes(state, Date.now(), 12), [state]);
  const heat = useMemo(() => publishTrend(state, 84, Date.now()), [state]);
  const building = state.releases.filter((r) => r.status === 'building');
  const maxTrend = Math.max(1, ...trend.flatMap((p) => [p.created, p.published]));

  return (
    <ProtoShell
      title="数据大屏"
      domain="可视化看板 V0"
      desc="一屏总览：趋势、漏斗、热力、节点与进行中构建（TICK 实时推进）。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="总数" value={m.total} tone="info" />
        <Kpi label="进行中" value={m.active} tone="violet" />
        <Kpi label="今日发布" value={m.publishedToday} tone="warning" />
        <Kpi label="失败" value={m.failed} tone="danger" />
      </KpiRow>

      <Section title="创建 / 发布趋势（14 天）">
        <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: 3, height: 64 }}>
          {trend.map((p) => (
            <View key={p.date} style={{ flex: 1, gap: 2, justifyContent: 'flex-end' }}>
              <View style={{ height: Math.max(2, (p.published / maxTrend) * 40), backgroundColor: TONE_COLOR.success, borderRadius: 2 }} />
              <View style={{ height: Math.max(2, (p.created / maxTrend) * 40), backgroundColor: TONE_COLOR.info, borderRadius: 2 }} />
            </View>
          ))}
        </View>
        <MonoText>■发布 ┄创建（上：发布 下：创建）</MonoText>
      </Section>

      <Section title="在途漏斗">
        <View style={{ gap: 6 }}>
          {funnel.map((f) => (
            <View key={f.key} style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Text style={{ color: palette.text2, fontSize: 12, width: 44 }}>{f.label}</Text>
              <View style={{ flex: 1, height: 10, borderRadius: 999, backgroundColor: palette.elevated, overflow: 'hidden' }}>
                <View style={{ height: '100%', width: `${(f.count / Math.max(1, ...funnel.map((x) => x.count))) * 100}%`, backgroundColor: TONE_COLOR[f.tone], borderRadius: 999 }} />
              </View>
              <MonoText color={palette.text}>{f.count}</MonoText>
            </View>
          ))}
        </View>
      </Section>

      <Section title="发布热力（12 周）">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 3 }}>
          {heat.map((p) => {
            const score = p.created + p.published * 2;
            const level = score === 0 ? 0 : score < 3 ? 1 : score < 6 ? 2 : score < 10 ? 3 : 4;
            return <View key={p.date} accessibilityLabel={`${p.date} 建 ${p.created} 发 ${p.published}`} style={{ width: 12, height: 12, borderRadius: 3, backgroundColor: HEAT_BG[level], borderWidth: 1, borderColor: palette.border }} />;
          })}
        </View>
        <MonoText>低 ■■■■ 高（一格一天，越深活动越多）</MonoText>
      </Section>

      <Section title="节点墙（演示集群）">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
          {nodes.map((n) => (
            <View
              key={n.id}
              accessibilityLabel={`节点 ${n.name} ${NODE_STATUS_META[n.status].label}`}
              style={{ width: 46, height: 46, borderRadius: 8, borderWidth: 1, borderColor: palette.border, alignItems: 'center', justifyContent: 'center', backgroundColor: `${TONE_COLOR[NODE_STATUS_META[n.status].tone]}33` }}
            >
              <Text style={{ color: palette.text2, fontSize: 12, fontWeight: '700' }}>{n.status === 'error' ? '✕' : n.load}</Text>
              <MonoText>{n.name}</MonoText>
            </View>
          ))}
        </View>
      </Section>

      <Section title="进行中构建">
        {building.length === 0 ? (
          <Muted>当前没有构建任务。</Muted>
        ) : (
          building.map((r) => (
            <View key={r.id} style={{ marginBottom: 10 }}>
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                <Tag label={r.id} color={TONE_COLOR.info} />
                <Text style={{ color: palette.text, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
              </View>
              <ProgressBar percent={r.progress} color={TONE_COLOR.info} />
            </View>
          ))
        )}
      </Section>
    </ProtoShell>
  );
}
