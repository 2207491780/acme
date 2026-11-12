import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { releaseGantt, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** V5 版本时间线（APP 形态）：窗口内横条 + 今日标线；进行中的单延伸至今日。 */
export default function GanttScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const gantt = useMemo(() => releaseGantt(state, Date.now(), 30, 10), [state]);
  const span = new Date(gantt.end).getTime() - new Date(gantt.start).getTime() || 1;
  const pos = (date: string) => ((new Date(date).getTime() - new Date(gantt.start).getTime()) / span) * 100;
  const width = (row: { start: string; end: string }) =>
    Math.max(3, ((new Date(row.end).getTime() - new Date(row.start).getTime()) / span) * 100);

  return (
    <ProtoShell
      title="版本时间线"
      domain="可视化看板 V5"
      desc="近 30 天发布跨度；红竖线为今日，进行中的单延伸到线处。"
      onBack={onBack}
    >
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginBottom: 6 }}>
        <MonoText>{gantt.start}</MonoText>
        <MonoText>{gantt.end}</MonoText>
      </View>

      <Section title={`时间条（${gantt.rows.length} 条）`}>
        <View style={{ gap: 10 }}>
          {gantt.rows.map((row) => (
            <View key={row.releaseId}>
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                <Tag label={row.releaseId} color={TONE_COLOR.muted} />
                <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>{row.title}</Text>
                <Tag label={statusLabel(row.status)} color={TONE_COLOR[statusTone(row.status)]} />
              </View>
              <View style={{ height: 14, borderRadius: 999, backgroundColor: palette.elevated, position: 'relative', overflow: 'hidden' }}>
                <View
                  style={{
                    position: 'absolute',
                    left: `${pos(row.start)}%`,
                    width: `${width(row)}%`,
                    top: 2,
                    bottom: 2,
                    borderRadius: 999,
                    backgroundColor: TONE_COLOR[row.tone],
                    opacity: 0.85,
                  }}
                />
                <View style={{ position: 'absolute', left: `${pos(gantt.today)}%`, top: 0, bottom: 0, width: 2, backgroundColor: TONE_COLOR.danger }} />
              </View>
            </View>
          ))}
        </View>
        <MonoText>■已发布 ■进行中 ■待发布 ■失败（红竖线 = 今日）</MonoText>
      </Section>

      <Muted style={{ fontSize: 11 }}>成熟化方向：按应用分组泳道与窗口缩放（联动 A4 日历）。</Muted>
    </ProtoShell>
  );
}
