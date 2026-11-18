import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { FLAG_SCOPE_LABEL, FEATURE_FLAGS, type FeatureFlag } from '@acme/core';
import { Button, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const SCOPE_TONE: Record<FeatureFlag['scope'], string> = {
  all: TONE_COLOR.violet,
  dev: TONE_COLOR.muted,
  test: TONE_COLOR.warning,
  prod: TONE_COLOR.danger,
};

/** W2 功能开关管理（APP 形态）：开关 + 影响面 + 未保存提示（仅本地生效）。 */
export default function FeatureFlagsScreen({ onBack }: { onBack: () => void }) {
  const [flags, setFlags] = useState<FeatureFlag[]>(FEATURE_FLAGS);
  const [dirty, setDirty] = useState<string[]>([]);
  const [flash, showFlash] = useFlash();

  const toggle = (key: string) => {
    setFlags((cur) => cur.map((f) => (f.key === key ? { ...f, enabled: !f.enabled } : f)));
    setDirty((cur) => (cur.includes(key) ? cur : [...cur, key]));
  };

  return (
    <ProtoShell
      title="功能开关管理"
      domain="内容与治理域 W2"
      desc="运行时行为开关；变更需保存留痕（审计口径）。"
      onBack={onBack}
      flash={flash}
    >
      <KpiRow>
        <Kpi label="开关总数" value={flags.length} tone="info" />
        <Kpi label="启用中" value={flags.filter((f) => f.enabled).length} tone="success" />
        <Kpi label="未保存" value={dirty.length} tone={dirty.length ? 'warning' : 'muted'} />
      </KpiRow>

      {flags.map((f) => (
        <Section key={f.key} title={f.name}>
          <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
            <Tag label={FLAG_SCOPE_LABEL[f.scope]} color={SCOPE_TONE[f.scope]} />
            {dirty.includes(f.key) ? <Tag label="未保存" color={TONE_COLOR.warning} /> : null}
            <Tag label={f.enabled ? '启用' : '禁用'} color={f.enabled ? TONE_COLOR.success : TONE_COLOR.muted} />
          </View>
          <Text style={{ color: palette.text2, fontSize: 12.5, lineHeight: 19 }}>{f.description}</Text>
          <MonoText color={palette.muted}>责任人：{f.owner} · 影响面：{f.impact}</MonoText>
          <Pressable
            accessibilityRole="switch"
            accessibilityLabel={`切换 ${f.name}`}
            accessibilityState={{ checked: f.enabled }}
            onPress={() => toggle(f.key)}
            style={[styles.track, { backgroundColor: f.enabled ? TONE_COLOR.success : palette.elevated }]}
          >
            <View style={[styles.knob, f.enabled && { alignSelf: 'flex-end' }]} />
          </Pressable>
        </Section>
      ))}

      <Button
        label={`保存变更（${dirty.length}）`}
        disabled={dirty.length === 0}
        onPress={() => {
          setDirty([]);
          showFlash('原型演示：已保存并写入审计口径');
        }}
      />
    </ProtoShell>
  );
}

const styles = {
  track: { width: 44, height: 24, borderRadius: 999, padding: 2, flexDirection: 'row' as const, marginTop: 8 },
  knob: { width: 20, height: 20, borderRadius: 999, backgroundColor: '#000000' },
};
