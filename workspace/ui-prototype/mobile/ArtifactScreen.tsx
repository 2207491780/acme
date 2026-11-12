import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { artifactInventory, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** R3 制品库（APP 形态）：跨单产物聚合 + 应用过滤 + 下载演示。 */
export default function ArtifactScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const rows = useMemo(() => artifactInventory(state), [state]);
  const [appId, setAppId] = useState('all');
  const [flash, showFlash] = useFlash();
  const filtered = rows.filter((r) => appId === 'all' || r.appId === appId);

  return (
    <ProtoShell
      title="制品库"
      domain="发布工程域 R3"
      desc="跨发布单的产物聚合；下载为演示口径。"
      onBack={onBack}
      flash={flash}
    >
      <KpiRow>
        <Kpi label="产物总数" value={rows.length} tone="info" />
        <Kpi label="已发布" value={rows.filter((r) => r.status === 'published').length} tone="success" />
        <Kpi label="筛选结果" value={filtered.length} tone="violet" />
      </KpiRow>

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        <Chip label="全部应用" active={appId === 'all'} onPress={() => setAppId('all')} />
        {state.apps.map((a) => (
          <Chip key={a.id} label={a.name} active={appId === a.id} onPress={() => setAppId(a.id)} />
        ))}
      </View>

      <Section title={`制品（${filtered.length}）`}>
        {filtered.length === 0 ? (
          <Muted>没有匹配的产物。</Muted>
        ) : (
          filtered.map((r) => (
            <View key={`${r.releaseId}-${r.name}`} style={styles.row}>
              <View style={{ flex: 1, gap: 3 }}>
                <Text style={{ color: palette.text, fontSize: 12.5 }}>{r.name}</Text>
                <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                  <Tag label={`v${r.version}`} color={TONE_COLOR.info} />
                  <Tag label={r.env} color={TONE_COLOR.muted} />
                  <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
                  <MonoText>{r.releaseId}</MonoText>
                </View>
              </View>
              <View style={{ alignItems: 'flex-end', gap: 4 }}>
                <Text style={{ color: palette.text, fontSize: 12 }}>{r.size}</Text>
                <Button label="下载" variant="ghost" small onPress={() => showFlash('原型演示：下载仅本地模拟')} />
              </View>
            </View>
          ))
        )}
      </Section>
      <Muted style={{ fontSize: 11 }}>保留策略（prod 90 天 / test 30 天）成熟化时接入。</Muted>
    </ProtoShell>
  );
}

const styles = {
  row: {
    flexDirection: 'row' as const,
    justifyContent: 'space-between' as const,
    alignItems: 'center' as const,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
    gap: 8,
  },
};
