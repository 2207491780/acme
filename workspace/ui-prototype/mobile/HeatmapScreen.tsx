import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { metrics, publishHeatmap } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette } from '../../theme';

const HEAT_BG = [
  'transparent',
  'rgba(111,146,196,0.22)',
  'rgba(111,146,196,0.42)',
  'rgba(111,146,196,0.68)',
  'rgba(111,146,196,1)',
];

const WEEKS = [12, 26] as const;

/** V2 发布热力图（APP 形态）：活动格 + 汇总 KPI；点击格子经无障碍标签播报明细。 */
export default function HeatmapScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [weeks, setWeeks] = useState<12 | 26>(12);
  const cells = useMemo(() => publishHeatmap(state, weeks, Date.now()), [state, weeks]);
  const m = useMemo(() => metrics(state), [state]);
  const activeDays = cells.filter((c) => c.level > 0).length;
  const busiest = [...cells].sort((a, b) => b.created + b.published * 2 - (a.created + a.published * 2))[0];

  return (
    <ProtoShell
      title="发布热力图"
      domain="可视化看板 V2"
      desc="一格一天，颜色越深活动越多（活动分 = 创建 + 发布×2）。"
      onBack={onBack}
    >
      <View style={{ flexDirection: 'row', gap: 8, marginBottom: 4 }}>
        {WEEKS.map((w) => (
          <Button key={w} label={`${w} 周`} variant={weeks === w ? 'primary' : 'ghost'} small onPress={() => setWeeks(w)} />
        ))}
      </View>

      <KpiRow>
        <Kpi label="活跃天数" value={`${activeDays}`} tone="info" />
        <Kpi label="累计发布" value={m.published} tone="success" />
        <Kpi label="最活跃" value={busiest?.date.slice(5) ?? '—'} tone="violet" />
      </KpiRow>

      <Section title={`活动格（${weeks} 周 × 7 天）`}>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 3 }}>
          {cells.map((c) => (
            <View
              key={c.date}
              accessibilityLabel={`${c.date}，创建 ${c.created}，发布 ${c.published}`}
              style={{ width: 13, height: 13, borderRadius: 3, backgroundColor: HEAT_BG[c.level], borderWidth: 1, borderColor: palette.border }}
            />
          ))}
        </View>
        <MonoText>首日 {cells[0]?.date} · 末日 {cells.at(-1)?.date}</MonoText>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 6 }}>
          <MonoText>低</MonoText>
          {HEAT_BG.map((bg, i) => (
            <View key={i} style={{ width: 11, height: 11, borderRadius: 3, backgroundColor: bg, borderWidth: 1, borderColor: palette.border }} />
          ))}
          <MonoText>高</MonoText>
        </View>
      </Section>

      <Muted style={{ fontSize: 11 }}>
        成熟化时支持点击格子下钻当日发布单列表（联动 A3），并按团队节奏自适应强度分位。
      </Muted>
    </ProtoShell>
  );
}
