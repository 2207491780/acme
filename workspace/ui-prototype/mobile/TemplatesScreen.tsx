import { useState } from 'react';
import { Text, View } from 'react-native';
import { TEMPLATES, type PipelineTemplate } from '@acme/core';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** B2 流水线模板（APP 形态）：模板选择与阶段构成。 */
export default function TemplatesScreen({ onBack }: { onBack: () => void }) {
  const [selectedId, setSelectedId] = useState(TEMPLATES[0]?.id ?? '');
  const [flash, showFlash] = useFlash();
  const selected: PipelineTemplate | undefined = TEMPLATES.find((t) => t.id === selectedId);
  const autoCount = selected?.stages.filter((s) => s.kind === 'auto').length ?? 0;
  const totalMin = selected?.stages.reduce((n, s) => n + s.estimateMin, 0) ?? 0;

  return (
    <ProtoShell
      title="流水线模板"
      domain="流程配置域 B2"
      desc="按平台维护阶段模板；自动阶段估时、人工阶段签核。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {TEMPLATES.map((t) => (
          <Chip key={t.id} label={t.name} active={t.id === selectedId} onPress={() => setSelectedId(t.id)} />
        ))}
      </View>

      {selected ? (
        <>
          <KpiRow>
            <Kpi label="自动阶段" value={autoCount} tone="info" />
            <Kpi label="人工阶段" value={selected.stages.length - autoCount} tone="warning" />
            <Kpi label="预估耗时" value={`${totalMin}′`} tone="success" />
          </KpiRow>

          <Section title={`阶段编排 · ${selected.name}`}>
            <View style={{ gap: 8 }}>
              {selected.stages.map((s, i) => (
                <View key={s.key} style={styles.stage}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    <Text style={{ color: palette.muted, fontSize: 11, width: 18 }}>{i + 1}</Text>
                    <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700', flex: 1 }}>{s.label}</Text>
                    <Tag label={s.kind === 'auto' ? '自动' : '人工'} color={s.kind === 'auto' ? TONE_COLOR.info : TONE_COLOR.warning} />
                  </View>
                  <MonoText color={palette.muted}>
                    {s.kind === 'manual' ? '等待审批签核，超时升级' : `失败即中止 · 约 ${s.estimateMin} 分钟`}
                  </MonoText>
                </View>
              ))}
            </View>
            <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
              <Button label="复制模板" variant="ghost" small onPress={() => showFlash('原型演示：复制模板仅本地生效')} />
              <Button label="以此模板发起" small onPress={() => showFlash('原型演示：发起发布仅本地生效')} />
            </View>
          </Section>
        </>
      ) : (
        <Muted>暂无模板。</Muted>
      )}
    </ProtoShell>
  );
}

const styles = {
  stage: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
    gap: 4,
  },
};
