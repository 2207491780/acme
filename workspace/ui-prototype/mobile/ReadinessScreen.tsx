import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { readinessReport, type ReadinessReport } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const STATE_COLOR = { pass: TONE_COLOR.success, warn: TONE_COLOR.warning, fail: TONE_COLOR.danger };

/** R1 发布就绪度检查（APP 形态）：六项 Go/No-Go 矩阵。 */
export default function ReadinessScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [releaseId, setReleaseId] = useState(state.releases[0]?.id ?? '');
  const [flash, showFlash] = useFlash();
  const report: ReadinessReport | null = useMemo(
    () => readinessReport(state, releaseId, Date.now()),
    [state, releaseId],
  );

  return (
    <ProtoShell
      title="发布就绪度检查"
      domain="发布工程域 R1"
      desc="Go/No-Go 六项门禁；prod 冻结与缺签为硬阻塞。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {state.releases.slice(0, 8).map((r) => (
          <Chip key={r.id} label={r.id} active={r.id === releaseId} onPress={() => setReleaseId(r.id)} />
        ))}
      </View>

      {report ? (
        <>
          <KpiRow>
            <Kpi label="就绪度评分" value={report.score} tone={report.score === 100 ? 'success' : 'warning'} />
            <Kpi label="结论" value={report.ready ? 'GO' : 'NO-GO'} tone={report.ready ? 'success' : 'danger'} />
            <Kpi label="阻塞失败" value={report.items.filter((i) => i.blocking && !i.pass).length} tone="danger" />
          </KpiRow>

          <Section title={`检查矩阵 · ${report.releaseId}`}>
            {report.items.map((item) => {
              const tone = item.pass ? 'pass' : item.blocking ? 'fail' : 'warn';
              return (
                <View key={item.key} style={styles.row}>
                  <Text style={{ color: STATE_COLOR[tone as keyof typeof STATE_COLOR], fontSize: 14 }}>
                    {item.pass ? '✓' : '✕'}
                  </Text>
                  <View style={{ flex: 1 }}>
                    <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                      <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700' }}>{item.label}</Text>
                      {item.blocking ? <Tag label="阻塞项" color={TONE_COLOR.danger} /> : !item.pass ? <Tag label="非阻塞" color={TONE_COLOR.warning} /> : null}
                    </View>
                    <Text style={{ color: palette.text2, fontSize: 12, marginTop: 2 }}>{item.note}</Text>
                  </View>
                </View>
              );
            })}
          </Section>

          <Button
            label={report.ready ? '确认执行发布（GO）' : 'NO-GO：存在阻塞项'}
            variant={report.ready ? 'primary' : 'danger'}
            onPress={() => showFlash(report.ready ? '原型演示：发布仅本地生效' : '原型演示：请先整改阻塞项')}
          />
        </>
      ) : (
        <Muted>请选择发布单。</Muted>
      )}
    </ProtoShell>
  );
}

const styles = {
  row: { flexDirection: 'row' as const, gap: 10, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: palette.border },
};
