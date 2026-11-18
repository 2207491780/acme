import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { fmtDate, RISK_LABEL, rollbackOptions, type RollbackOption } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const RISK_COLOR: Record<RollbackOption['risk'], string> = {
  low: TONE_COLOR.success,
  medium: TONE_COLOR.warning,
  high: TONE_COLOR.danger,
};
const CHECKLIST = ['已冻结当前发布窗口', '已确认产物与数据快照可追溯', '高风险操作已双人复核'];

/** A5 回滚向导（APP 形态）：选发布 → 选版本 → 安全确认 → 执行（仅本地生效）。 */
export default function RollbackScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [step, setStep] = useState(0);
  const [releaseId, setReleaseId] = useState<string | null>(null);
  const [option, setOption] = useState<RollbackOption | null>(null);
  const [checked, setChecked] = useState<number[]>([]);
  const [flash, showFlash] = useFlash();
  const [done, setDone] = useState(false);

  const release = releases.find((r) => r.id === releaseId);
  const options = release ? rollbackOptions({ releases }, release, Date.now()) : [];
  const canNext =
    (step === 0 && !!releaseId) || (step === 1 && !!option) || (step === 2 && checked.length === CHECKLIST.length);

  return (
    <ProtoShell
      title="回滚向导"
      domain="发布单域 A5"
      desc={`步骤 ${done ? 4 : step + 1}/4：${done ? '完成' : ['选择发布单', '选择回滚版本', '安全确认'][step] ?? ''}`}
      onBack={onBack}
      flash={flash}
    >
      {done ? (
        <Section title="回滚已完成（原型演示）">
          <Text style={{ color: palette.text, fontSize: 13, lineHeight: 20 }}>
            {release?.appId} 已从 v{release?.version} 回滚至 v{option?.version}。正式版将联动动态流与审计。
          </Text>
          <Button
            label="再来一次"
            variant="ghost"
            small
            onPress={() => {
              setStep(0);
              setReleaseId(null);
              setOption(null);
              setChecked([]);
              setDone(false);
            }}
          />
        </Section>
      ) : (
        <>
          {step === 0 ? (
            <Section title="① 选择要回滚的发布单">
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                {releases
                  .filter((r) => ['published', 'failed', 'approved'].includes(r.status))
                  .map((r) => (
                    <Chip key={r.id} label={r.id} active={releaseId === r.id} onPress={() => setReleaseId(r.id)} />
                  ))}
              </View>
            </Section>
          ) : step === 1 ? (
            <Section title="② 选择回滚目标版本">
              {options.length === 0 ? (
                <Muted>没有可回滚的历史版本。</Muted>
              ) : (
                options.map((o) => (
                  <Pressable
                    key={o.version}
                    accessibilityRole="radio"
                    accessibilityLabel={`回滚到 v${o.version}，${RISK_LABEL[o.risk]}`}
                    accessibilityState={{ selected: option?.version === o.version }}
                    onPress={() => setOption(o)}
                    style={[styles.option, option?.version === o.version && { borderColor: TONE_COLOR.info }]}
                  >
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                      <Tag label={RISK_LABEL[o.risk]} color={RISK_COLOR[o.risk]} />
                      <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700' }}>v{o.version}</Text>
                      <MonoText>{fmtDate(o.releasedAt)}</MonoText>
                    </View>
                    <MonoText color={palette.text2}>{o.note}</MonoText>
                  </Pressable>
                ))
              )}
            </Section>
          ) : (
            <Section title="③ 安全确认">
              {CHECKLIST.map((label, i) => (
                <Pressable
                  key={label}
                  accessibilityRole="checkbox"
                  accessibilityLabel={label}
                  accessibilityState={{ checked: checked.includes(i) }}
                  onPress={() => setChecked((cur) => (cur.includes(i) ? cur.filter((x) => x !== i) : [...cur, i]))}
                  style={styles.checkRow}
                >
                  <View style={[styles.box, checked.includes(i) && { backgroundColor: TONE_COLOR.success, borderColor: TONE_COLOR.success }]}>
                    {checked.includes(i) ? <Text style={{ color: '#000', fontSize: 11 }}>✓</Text> : null}
                  </View>
                  <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }}>{label}</Text>
                </Pressable>
              ))}
              {option && release ? (
                <MonoText color={palette.text2}>
                  将执行：{release.appId} v{release.version} → v{option.version}（{release.env}）
                </MonoText>
              ) : null}
            </Section>
          )}

          <View style={{ flexDirection: 'row', gap: 10, marginTop: 4 }}>
            <Button
              label={step === 0 ? '重置' : '上一步'}
              variant="ghost"
              onPress={() => (step === 0 ? (setReleaseId(null), setOption(null), setChecked([])) : setStep(step - 1))}
            />
            {step < 2 ? (
              <Button label="下一步" disabled={!canNext} onPress={() => setStep(step + 1)} />
            ) : (
              <Button
                label="执行回滚"
                variant="danger"
                disabled={!canNext}
                onPress={() => {
                  setDone(true);
                  showFlash('原型演示：回滚仅本地生效，未执行真实操作');
                }}
              />
            )}
          </View>
        </>
      )}
    </ProtoShell>
  );
}

const styles = {
  option: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    marginBottom: 8,
    backgroundColor: palette.elevated,
  },
  checkRow: { flexDirection: 'row' as const, gap: 10, alignItems: 'center' as const, paddingVertical: 6 },
  box: {
    width: 18,
    height: 18,
    borderRadius: 5,
    borderWidth: 1.5,
    borderColor: palette.border,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
  },
};
