import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { ENV_VARS, envOverview, fmtFull } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const ENV_TONE = { dev: TONE_COLOR.muted, test: TONE_COLOR.warning, prod: TONE_COLOR.danger } as const;

/** H1 环境中心（APP 形态）：三环境卡片 + 单环境详情（历史/变量脱敏/冻结）。 */
export default function EnvScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const envs = useMemo(() => envOverview(state), [state]);
  const [selected, setSelected] = useState<'dev' | 'test' | 'prod'>('prod');
  const current = envs.find((e) => e.env === selected)!;
  const history = state.releases.filter((r) => r.env === selected).slice(0, 5);
  const vars = ENV_VARS.filter((v) => v.scope === selected);

  return (
    <ProtoShell
      title="环境中心"
      domain="环境域 H1"
      desc="dev / test / prod 三态概览与详情；prod 冻结 22:00-08:00 + 双人审批。"
      onBack={onBack}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {envs.map((e) => (
          <Chip key={e.env} label={`${e.label}（${e.deployedVersion ? `v${e.deployedVersion}` : '未部署'}）`} active={selected === e.env} onPress={() => setSelected(e.env)} />
        ))}
      </View>

      <KpiRow>
        <Kpi label="当前部署" value={current.deployedVersion ? `v${current.deployedVersion}` : '—'} tone={current.tone} />
        <Kpi label="累计发布" value={current.publishes} tone="info" />
        <Kpi label="进行中" value={current.inFlight} tone="violet" />
        <Kpi label="变量数" value={current.varCount} tone="muted" />
      </KpiRow>

      <Section title={`冻结策略 · ${envLabel2(current.env)}`}>
        <Text style={{ color: palette.text2, fontSize: 13 }}>{current.freeze}</Text>
      </Section>

      <Section title="最近发布历史">
        {history.length === 0 ? (
          <Muted>该环境暂无发布记录。</Muted>
        ) : (
          history.map((r) => (
            <View key={r.id} style={{ flexDirection: 'row', gap: 6, alignItems: 'center', paddingVertical: 5 }}>
              <Tag label={r.id} color={ENV_TONE[r.env as keyof typeof ENV_TONE]} />
              <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
              <MonoText>v{r.version} · {fmtFull(r.updatedAt).slice(5, 16)}</MonoText>
            </View>
          ))
        )}
      </Section>

      <Section title="环境变量（secret 脱敏）">
        {vars.length === 0 ? (
          <Muted>该环境暂无预置变量。</Muted>
        ) : (
          vars.map((v) => (
            <View key={v.key} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 5 }}>
              <Text style={{ color: palette.text, fontSize: 12, flex: 1 }}>{v.secret ? '🔒 ' : ''}{v.key}</Text>
              <Text style={{ color: v.secret ? TONE_COLOR.warning : palette.text2, fontSize: 12 }}>{v.value}</Text>
            </View>
          ))
        )}
        <Muted style={{ fontSize: 11 }}>真实凭证只在环境侧（.ai/env 纪律）。</Muted>
      </Section>
    </ProtoShell>
  );
}

function envLabel2(env: 'dev' | 'test' | 'prod'): string {
  return { dev: '开发', test: '测试', prod: '生产' }[env];
}
