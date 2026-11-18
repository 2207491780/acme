import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { grayPlans } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, ProgressBar, Tag } from '../../components/ui';
import { ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** S2 灰度发布管理（APP 形态）：阶段进度条 + 成功率 + 暂停/恢复（仅本地生效）。 */
export default function GrayScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const plans = useMemo(() => grayPlans(state, Date.now()), [state]);
  const [flash, showFlash] = useFlash();

  return (
    <ProtoShell
      title="灰度发布管理"
      domain="稳定性域 S2"
      desc="5% → 50% → 100% 放量计划；异常可暂停（联动 H1 冻结期校验）。"
      onBack={onBack}
      flash={flash}
    >
      {plans.length === 0 ? (
        <Muted>暂无灰度计划。</Muted>
      ) : (
        plans.map((plan) => {
          const current = plan.stages.find((s) => s.state === 'active');
          return (
            <Section key={plan.id} title={`${plan.appName} · v${plan.version}`}>
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
                {plan.paused ? <Tag label="已暂停" color={TONE_COLOR.warning} /> : null}
                <Tag label={`当前 ${current?.percent ?? 100}%`} color={TONE_COLOR.info} />
              </View>
              <ProgressBar percent={current?.percent ?? 100} color={plan.paused ? TONE_COLOR.warning : TONE_COLOR.info} />
              <View style={{ gap: 8, marginTop: 10 }}>
                {plan.stages.map((s) => (
                  <View key={s.percent} style={[styles.stage, s.state === 'active' && { borderColor: TONE_COLOR.info }]}>
                    <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                      <Tag
                        label={`${s.percent}% ${s.state === 'done' ? '已完成' : s.state === 'active' ? '进行中' : '待放量'}`}
                        color={s.state === 'done' ? TONE_COLOR.success : s.state === 'active' ? TONE_COLOR.info : palette.muted}
                      />
                      {s.state !== 'pending' ? <Text style={{ color: palette.text, fontSize: 12 }}>成功率 {s.successRate}%</Text> : null}
                    </View>
                    <Text style={{ color: palette.text2, fontSize: 12 }}>{s.feedback}</Text>
                  </View>
                ))}
              </View>
              <Button
                label={plan.paused ? '恢复放量' : '暂停放量'}
                variant="ghost"
                small
                onPress={() => showFlash(plan.paused ? '原型演示：恢复放量' : '原型演示：暂停放量（仅本地生效）')}
              />
            </Section>
          );
        })
      )}
      <Muted style={{ fontSize: 11 }}>成熟化方向：分群放量、错误率超阈值自动暂停（联动 A5 回滚）。</Muted>
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
