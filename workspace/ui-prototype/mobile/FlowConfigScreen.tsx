import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { ENV_VARS, envLabel, FLOW_RULES, MEMBERS, type FlowRule, type Env } from '@acme/core';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const ENV_TONE: Record<Env, string> = { dev: TONE_COLOR.muted, test: TONE_COLOR.warning, prod: TONE_COLOR.danger };

/** B1 审批流配置（APP 形态）：每环境的审批人数/审批人/自动发布（仅本地生效）。 */
export default function FlowConfigScreen({ onBack }: { onBack: () => void }) {
  const [rules, setRules] = useState<FlowRule[]>(FLOW_RULES);
  const [flash, showFlash] = useFlash();

  const patch = (id: string, p: Partial<FlowRule>) => setRules((cur) => cur.map((r) => (r.id === id ? { ...r, ...p } : r)));

  const toggleApprover = (rule: FlowRule, name: string) => {
    patch(rule.id, {
      approvers: rule.approvers.includes(name) ? rule.approvers.filter((x) => x !== name) : [...rule.approvers, name],
    });
  };

  return (
    <ProtoShell
      title="审批流配置"
      domain="流程配置域 B1"
      desc="按环境定义审批链规则；作用于 A1 审批详情的流程口径。"
      onBack={onBack}
      flash={flash}
    >
      {rules.map((rule) => (
        <Section key={rule.id} title={envLabel(rule.env)}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 8 }}>
            <Muted style={{ fontSize: 12 }}>最少审批人</Muted>
            {[0, 1, 2, 3].map((n) => (
              <Chip key={n} label={String(n)} active={rule.minApprovers === n} onPress={() => patch(rule.id, { minApprovers: n })} />
            ))}
          </View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
            {MEMBERS.map((m) => (
              <Chip key={m.id} label={m.name} active={rule.approvers.includes(m.name)} onPress={() => toggleApprover(rule, m.name)} />
            ))}
          </View>
          <Pressable
            accessibilityRole="switch"
            accessibilityLabel={`${envLabel(rule.env)} 通过后自动发布`}
            accessibilityState={{ checked: rule.autoOnPass }}
            onPress={() => patch(rule.id, { autoOnPass: !rule.autoOnPass })}
            style={[styles.switchRow, { borderColor: rule.autoOnPass ? TONE_COLOR.success : palette.border }]}
          >
            <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }}>通过后自动发布</Text>
            <View style={[styles.knobTrack, { backgroundColor: rule.autoOnPass ? TONE_COLOR.success : palette.elevated }]}>
              <View style={[styles.knob, rule.autoOnPass && { alignSelf: 'flex-end' }]} />
            </View>
          </Pressable>
          <Tag label={rule.minApprovers === 0 ? '免审' : `需 ${rule.minApprovers} 人`} color={ENV_TONE[rule.env]} />
        </Section>
      ))}
      <Button label="保存配置（原型）" onPress={() => showFlash('原型演示：配置仅本地生效')} />
      <Muted style={{ fontSize: 11 }}>
        密钥纪律提醒：真实凭证只在环境侧（当前展示 {ENV_VARS.filter((v) => v.secret).length} 个脱敏示例）。
      </Muted>
    </ProtoShell>
  );
}

const styles = {
  switchRow: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    borderWidth: 1,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
  },
  knobTrack: { width: 40, height: 22, borderRadius: 999, padding: 2, flexDirection: 'row' as const },
  knob: { width: 18, height: 18, borderRadius: 999, backgroundColor: '#000000' },
};
