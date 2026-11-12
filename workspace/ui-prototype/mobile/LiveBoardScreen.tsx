import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { platformShort, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, ProgressBar, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section, ToneDot } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** C2 实时大盘（APP 形态）：构建进度（TICK 自动刷新）/ 测试 / 待发布 / 队列。 */
export default function LiveBoardScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const building = state.releases.filter((r) => r.status === 'building');
  const testing = state.releases.filter((r) => r.status === 'testing');
  const approved = state.releases.filter((r) => r.status === 'approved');
  const queue = useMemo(() => state.releases.filter((r) => r.status === 'draft').slice(0, 5), [state.releases]);

  return (
    <ProtoShell
      title="实时大盘"
      domain="观测域 C2"
      desc="进行中流水线与构建队列；构建进度由 TICK 每 1.2s 自动推进。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="构建中" value={building.length} tone="info" />
        <Kpi label="测试中" value={testing.length} tone="violet" />
        <Kpi label="待发布" value={approved.length} tone="warning" />
        <Kpi label="排队" value={queue.length} tone="muted" />
      </KpiRow>

      <Section title="流水线 · 构建中">
        {building.length === 0 ? (
          <Muted>当前没有构建。</Muted>
        ) : (
          <View style={{ gap: 10 }}>
            {building.map((r) => (
              <View key={r.id} style={styles.card}>
                <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 6 }}>
                  <Tag label={r.id} color={TONE_COLOR.info} />
                  <Tag label={platformShort(r.platform)} color={TONE_COLOR.violet} />
                  <Text style={{ color: palette.text, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
                </View>
                <ProgressBar percent={r.progress} color={TONE_COLOR.info} />
                <MonoText color={palette.muted}>{r.progress}% · v{r.version} · {r.owner}</MonoText>
              </View>
            ))}
          </View>
        )}
      </Section>

      <Section title="流水线 · 测试中 / 待发布">
        {[...testing, ...approved].length === 0 ? (
          <Muted>空。</Muted>
        ) : (
          [...testing, ...approved].map((r) => (
            <View key={r.id} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 5 }}>
              <ToneDot tone={statusTone(r.status)} />
              <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
              <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
            </View>
          ))
        )}
      </Section>

      <Section title="构建队列（FIFO）">
        {queue.length === 0 ? (
          <Muted>队列空闲。</Muted>
        ) : (
          queue.map((r, i) => (
            <View key={r.id} style={{ flexDirection: 'row', gap: 10, alignItems: 'center', paddingVertical: 4 }}>
              <View style={{ width: 20, height: 20, borderRadius: 99, borderWidth: 1, borderColor: palette.border, alignItems: 'center', justifyContent: 'center' }}>
                <MonoText>{i + 1}</MonoText>
              </View>
              <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
              <MonoText>{r.id}</MonoText>
            </View>
          ))
        )}
      </Section>
    </ProtoShell>
  );
}

const styles = {
  card: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
  },
};
