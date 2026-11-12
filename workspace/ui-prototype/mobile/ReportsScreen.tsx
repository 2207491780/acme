import { useMemo } from 'react';
import { View } from 'react-native';
import { cycleTimeStats, failureReasons, metrics, publishTrend } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted } from '../../components/ui';
import { BarList, Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** C1 数据报表（APP 形态）：趋势/成功率/归因/耗时，由正式 store 只读推导。 */
export default function ReportsScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const trend = useMemo(() => publishTrend(state, 14, Date.now()), [state]);
  const reasons = useMemo(() => failureReasons(state), [state]);
  const cycle = useMemo(() => cycleTimeStats(state), [state]);
  const m = useMemo(() => metrics(state), [state]);
  const successRate = m.total ? Math.round((m.published / m.total) * 100) : 0;
  const maxTrend = Math.max(1, ...trend.flatMap((p) => [p.created, p.published]));

  return (
    <ProtoShell
      title="数据报表"
      domain="观测域 C1"
      desc="发布效率与健康度；数据完全来自正式 store（只读）。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="发布单总数" value={m.total} tone="info" />
        <Kpi label="累计成功率" value={`${successRate}%`} tone="success" />
        <Kpi label="进行中" value={m.active} tone="violet" />
        <Kpi label="失败待处理" value={m.failed} tone="danger" />
      </KpiRow>

      <Section title="创建 / 发布趋势（近 14 天）">
        <View style={{ gap: 5 }}>
          {trend.map((p) => (
            <View key={p.date} style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <MonoText>{p.date.slice(5)}</MonoText>
              <View style={{ flex: 1, height: 5, borderRadius: 999, backgroundColor: palette.elevated, overflow: 'hidden' }}>
                <View style={{ height: '100%', width: `${(p.created / maxTrend) * 100}%`, backgroundColor: TONE_COLOR.info }} />
              </View>
              <MonoText>{`建${p.created} 发${p.published}`}</MonoText>
            </View>
          ))}
        </View>
      </Section>

      <Section title="失败归因">
        {reasons.length ? <BarList items={reasons} /> : <Muted>当前没有失败发布单。</Muted>}
      </Section>

      <Section title="阶段平均耗时（小时）">
        {cycle.length ? <BarList items={cycle} /> : <Muted>尚无已完成发布。</Muted>}
      </Section>

      <Muted style={{ fontSize: 11 }}>
        失败归因基于备注关键词口径（快照/依赖/环境/其他），成熟化时改为结构化字段。
      </Muted>
    </ProtoShell>
  );
}
