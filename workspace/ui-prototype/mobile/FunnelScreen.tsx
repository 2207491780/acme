import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { releaseFunnel } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR, ACCENTS } from '../../theme';

/** V3 发布漏斗（APP 形态）：居中递减条 + 相邻转化率。 */
export default function FunnelScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const funnel = useMemo(() => releaseFunnel(state), [state]);
  const max = Math.max(1, ...funnel.map((f) => f.count));
  const failed = state.releases.filter((r) => r.status === 'failed').length;
  const cancelled = state.releases.filter((r) => r.status === 'cancelled').length;
  const entered = funnel[0].count + failed + cancelled;

  return (
    <ProtoShell
      title="发布漏斗"
      domain="可视化看板 V3"
      desc="在途五阶段留存截面；失败与取消在漏斗场外单独计数。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="漏斗内" value={funnel.reduce((n, f) => n + f.count, 0)} tone="info" />
        <Kpi label="进入量" value={entered} tone="violet" />
        <Kpi label="末态已发布" value={funnel.at(-1)!.count} tone="success" />
        <Kpi label="场外" value={failed + cancelled} tone="danger" />
      </KpiRow>

      <Section title="阶段留存">
        {funnel.map((f, i) => {
          const prev = i === 0 ? null : funnel[i - 1];
          const conv = prev && prev.count > 0 ? Math.round((f.count / prev.count) * 100) : null;
          return (
            <View key={f.key} style={{ alignItems: 'center', marginBottom: 6 }}>
              <View
                style={{
                  width: `${Math.max(24, (f.count / max) * 100)}%`,
                  borderWidth: 1,
                  borderColor: palette.border,
                  borderTopWidth: i === 0 ? 3 : 1,
                  borderTopColor: i === 0 ? ACCENTS.cyan : palette.border,
                  borderRadius: 10,
                  backgroundColor: palette.elevated,
                  paddingVertical: 8,
                  paddingHorizontal: 12,
                  flexDirection: 'row',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700' }}>{f.label}</Text>
                <Text style={{ color: TONE_COLOR[f.tone], fontSize: 15, fontWeight: '800' }}>{f.count}</Text>
              </View>
              {conv !== null && prev ? (
                <MonoText color={palette.muted}>{`↑ 自「${prev.label}」转化 ${conv}%`}</MonoText>
              ) : null}
            </View>
          );
        })}
      </Section>

      <Muted style={{ fontSize: 11 }}>
        成熟化时补各阶段平均停留时长（数据源同 C1 的 cycleTimeStats）。
      </Muted>
    </ProtoShell>
  );
}
