import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { ENV_VARS, healthSeries, metrics, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { BarList, Chip, Kpi, KpiRow, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const METRICS = [
  { key: 'uptime' as const, label: '可用率', unit: '%' },
  { key: 'latencyMs' as const, label: '延迟', unit: 'ms' },
  { key: 'errorRate' as const, label: '错误率', unit: '%' },
];

/** E1 应用详情（APP 形态）：健康指标分档柱状 + 环境变量脱敏 + 发布历史。 */
export default function AppDetailScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [appId, setAppId] = useState(state.apps[0]?.id ?? '');
  const [metric, setMetric] = useState<'uptime' | 'latencyMs' | 'errorRate'>('uptime');
  const app = state.apps.find((a) => a.id === appId);
  const series = useMemo(() => (app ? healthSeries(app.id, Date.now()) : []), [app]);
  const releases = state.releases.filter((r) => r.appId === appId).slice(0, 5);
  const appMetrics = useMemo(
    () => (app ? metrics({ ...state, releases: state.releases.filter((r) => r.appId === app.id) }) : null),
    [state, app],
  );

  // 连续值 → 分档计数，用 BarList 呈现分布
  const dist = useMemo(() => {
    const values = series.map((p) => p[metric]);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const buckets = [0, 0, 0, 0];
    for (const v of values) {
      const idx = Math.min(3, Math.floor(((v - min) / (max - min || 1)) * 4));
      buckets[idx] += 1;
    }
    const meta = METRICS.find((m) => m.key === metric)!;
    return buckets.map((count, i) => ({
      name: `${meta.label} ${meta.unit} · 档 ${i + 1}`,
      count,
      tone: (['info', 'violet', 'warning', 'danger'] as const)[i],
    }));
  }, [series, metric]);

  return (
    <ProtoShell
      title="应用详情"
      domain="应用与配置域 E1"
      desc="健康分布、发布历史与环境变量（secret 脱敏）。"
      onBack={onBack}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {state.apps.map((a) => (
          <Chip key={a.id} label={a.name} active={a.id === appId} onPress={() => setAppId(a.id)} />
        ))}
      </View>

      {app ? (
        <>
          <KpiRow>
            <Kpi label="当前版本" value={`v${app.version}`} tone="info" />
            <Kpi label="发布单" value={appMetrics?.total ?? 0} tone="violet" />
            <Kpi label="负责人" value={app.owner} tone="muted" />
          </KpiRow>

          <Section title="健康指标分布（近 24 小时 · 演示数据）">
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
              {METRICS.map((m) => (
                <Chip key={m.key} label={m.label} active={metric === m.key} onPress={() => setMetric(m.key)} />
              ))}
            </View>
            <BarList items={dist} />
          </Section>

          <Section title="环境变量（secret 脱敏）">
            {ENV_VARS.map((v) => (
              <View key={v.key} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 5 }}>
                <Text style={{ color: palette.text, fontSize: 12, flex: 1 }}>{v.key}</Text>
                <Tag label={v.scope} color={TONE_COLOR.muted} />
                <MonoText color={v.secret ? TONE_COLOR.warning : palette.text2}>
                  {v.secret ? '🔒 ' : ''}{v.value}
                </MonoText>
              </View>
            ))}
            <Muted style={{ fontSize: 11, marginTop: 6 }}>真实凭证只存在于环境侧，页面只消费脱敏值。</Muted>
          </Section>

          <Section title="发布历史（最近）">
            <Muted style={{ fontSize: 12, marginBottom: 6 }}>{app.path}</Muted>
            {releases.map((r) => (
              <View key={r.id} style={{ flexDirection: 'row', gap: 6, alignItems: 'center', paddingVertical: 4 }}>
                <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
                <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
                <MonoText>v{r.version}</MonoText>
              </View>
            ))}
            <Button label="复制工程路径（原型）" variant="ghost" small onPress={() => undefined} />
          </Section>
        </>
      ) : null}
    </ProtoShell>
  );
}
