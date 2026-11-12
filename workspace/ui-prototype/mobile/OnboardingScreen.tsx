import { useState } from 'react';
import { Text, View } from 'react-native';
import { onboardingAutoState, ONBOARDING_STEPS } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** X3 新手引导清单（APP 形态）：真实 store 自动判定 + 手动确认 + 进度环数字。 */
export default function OnboardingScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [manual, setManual] = useState<string[]>([]);
  const steps = ONBOARDING_STEPS.map((step) => {
    const auto = onboardingAutoState(state, step);
    return { ...step, done: auto !== null ? auto : manual.includes(step.key) };
  });
  const doneCount = steps.filter((s) => s.done).length;

  return (
    <ProtoShell
      title="新手引导清单"
      domain="横向专项 X3"
      desc="上手六步走：三项真实数据自动判定，三项手动确认。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="完成进度" value={`${doneCount}/${steps.length}`} tone={doneCount === steps.length ? 'success' : 'info'} />
        <Kpi label="自动判定" value={steps.filter((s) => s.auto).length} tone="violet" />
        <Kpi label="手动确认" value={steps.filter((s) => !s.auto).length} tone="muted" />
      </KpiRow>

      {steps.map((step, i) => (
        <Section key={step.key} title={`${i + 1}. ${step.label}`}>
          <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
            {step.done ? (
              <Tag label="已完成" color={TONE_COLOR.success} />
            ) : (
              <Tag label={step.auto ? '自动判定' : '待完成'} color={step.auto ? TONE_COLOR.info : TONE_COLOR.muted} />
            )}
            {step.auto ? <MonoText color={palette.muted}>{`依据：${step.auto === 'has-published' ? '存在已发布单' : step.auto === 'has-approved' ? '存在待发布/已发布' : '存在产物'}`}</MonoText> : null}
          </View>
          <Text style={{ color: palette.text2, fontSize: 12.5, lineHeight: 19 }}>{step.desc}</Text>
          {!step.auto ? (
            <Button
              label={step.done ? '取消勾选' : '标记完成'}
              variant={step.done ? 'ghost' : 'primary'}
              small
              onPress={() => setManual((cur) => (cur.includes(step.key) ? cur.filter((k) => k !== step.key) : [...cur, step.key]))}
            />
          ) : (
            <Muted style={{ fontSize: 11 }}>已在正式 store 中检测到完成。</Muted>
          )}
        </Section>
      ))}

      {doneCount === steps.length ? (
        <Muted style={{ color: TONE_COLOR.success }}>🎉 上手完成，尽情探索其余原型！</Muted>
      ) : null}
    </ProtoShell>
  );
}

const styles = { hit: { flexDirection: 'row' as const, gap: 8 } };
void styles;
