import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { releaseDiff } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** R4 版本对比（APP 形态）：两条发布单的字段级 diff，变更行高亮。 */
export default function DiffScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [idA, setIdA] = useState(state.releases[0]?.id ?? '');
  const [idB, setIdB] = useState(state.releases[2]?.id ?? state.releases[1]?.id ?? '');
  const diff = useMemo(() => releaseDiff(state, idA, idB), [state, idA, idB]);
  const changedCount = diff.fields.filter((f) => f.changed).length;

  const pick = (side: 'a' | 'b', current: string) => {
    const idx = state.releases.findIndex((r) => r.id === current);
    const next = state.releases[(idx + 1) % state.releases.length];
    if (side === 'a') setIdA(next.id);
    else setIdB(next.id);
  };

  return (
    <ProtoShell
      title="版本对比"
      domain="发布工程域 R4"
      desc="字段级 diff；重复点同侧选择器可轮换发布单。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="对比字段" value={diff.fields.length} tone="info" />
        <Kpi label="差异" value={changedCount} tone={changedCount ? 'warning' : 'success'} />
        <Kpi label="相同" value={diff.fields.length - changedCount} tone="muted" />
      </KpiRow>

      <Section title={`基准：${diff.a?.id ?? '—'}`}>
        <MonoText color={palette.muted}>点击下方「换基准 / 换目标」轮换选择</MonoText>
      </Section>

      <View style={{ gap: 6 }}>
        {diff.fields.map((f) => (
          <View key={f.field} style={[styles.field, f.changed && { backgroundColor: 'rgba(207,161,79,0.10)' }]}>
            <Text style={{ color: palette.muted, fontSize: 11, width: 70 }}>{f.field}</Text>
            <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>{f.a}</Text>
            <Text style={{ color: f.changed ? TONE_COLOR.warning : palette.muted, fontSize: 12, width: 16, textAlign: 'center' }}>
              {f.changed ? '≠' : '='}
            </Text>
            <Text style={{ color: palette.text, fontSize: 12, flex: 1, fontWeight: f.changed ? '700' : '400' }} numberOfLines={1}>{f.b}</Text>
          </View>
        ))}
      </View>

      <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}>
        <Button label="换基准" variant="ghost" small onPress={() => pick('a', idA)} />
        <Button label="换目标" variant="ghost" small onPress={() => pick('b', idB)} />
      </View>
      <MonoText color={palette.muted}>基准 {diff.a?.id ?? '—'} · 目标 {diff.b?.id ?? '—'}</MonoText>
    </ProtoShell>
  );
}

const styles = {
  field: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: 8,
    paddingVertical: 6,
    paddingHorizontal: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: palette.border,
  },
};
