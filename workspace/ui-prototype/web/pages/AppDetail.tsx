import { useMemo, useState } from 'react';
import { Descriptions, Segmented, Select, Space, Table, Tag, Typography } from 'antd';
import { ApiOutlined, LockOutlined } from '@ant-design/icons';
import {
  ENV_VARS,
  fmtDate,
  HEALTH_LABELS,
  healthSeries,
  metrics,
  statusLabel,
  statusTone,
  TONE_ANTD,
} from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, MiniLine, Section } from '../components/blocks';

const { Text } = Typography;

/** E1 应用详情：健康曲线、环境变量（脱敏）、该应用发布历史（原型只读）。 */
export default function AppDetail() {
  const state = useStore((s) => s.state);
  const [appId, setAppId] = useState(state.apps[0]?.id ?? '');
  const [metric, setMetric] = useState<'uptime' | 'latencyMs' | 'errorRate'>('uptime');
  const app = state.apps.find((a) => a.id === appId);
  const series = useMemo(() => (app ? healthSeries(app.id, Date.now()) : []), [app]);
  const releases = state.releases.filter((r) => r.appId === appId).slice(0, 6);
  const m = useMemo(() => (app ? metrics({ ...state, releases: state.releases.filter((r) => r.appId === app.id) }) : null), [state, app]);

  const metricMeta = {
    uptime: { label: '可用率 %', color: 'var(--acid)', fmt: (v: number) => `${v}%` },
    latencyMs: { label: '延迟 ms', color: 'var(--blue)', fmt: (v: number) => `${v}ms` },
    errorRate: { label: '错误率 %', color: 'var(--red)', fmt: (v: number) => `${v}%` },
  } as const;

  return (
    <PageShell
      title="应用详情"
      domain="应用与配置域 E1"
      desc="单应用视角：健康历史曲线、发布历史、环境变量（secret 一律脱敏展示）。"
      extra={
        <Select
          style={{ width: 220 }}
          value={appId}
          onChange={setAppId}
          aria-label="选择应用"
          options={state.apps.map((a) => ({ value: a.id, label: a.name }))}
        />
      }
    >
      {!app ? null : (
        <>
          <KpiRow>
            <Kpi label="健康度" value={HEALTH_LABELS[app.health]} tone={app.health === 'healthy' ? 'success' : app.health === 'warning' ? 'warning' : 'danger'} />
            <Kpi label="当前版本" value={`v${app.version}`} tone="info" />
            <Kpi label="发布单" value={m?.total ?? 0} tone="violet" hint="全部环境累计" />
            <Kpi label="负责人" value={app.owner} tone="muted" />
          </KpiRow>

          <Section
            title={
              <Space size={6}>
                <ApiOutlined style={{ color: 'var(--accent)' }} /> 健康趋势（近 24 小时 · 演示数据）
              </Space>
            }
            extra={
              <Segmented
                size="small"
                value={metric}
                onChange={(v) => setMetric(v as typeof metric)}
                aria-label="切换健康指标"
                options={[
                  { value: 'uptime', label: '可用率' },
                  { value: 'latencyMs', label: '延迟' },
                  { value: 'errorRate', label: '错误率' },
                ]}
              />
            }
            style={{ marginBottom: 16 }}
          >
            <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>{metricMeta[metric].label}</div>
            <MiniLine values={series.map((p) => p[metric])} color={metricMeta[metric].color} height={90} />
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--muted)', marginTop: 4 }}>
              <span>{fmtDate(series[0]?.at ?? '')}</span>
              <span>当前 {metricMeta[metric].fmt(series.at(-1)![metric])}</span>
              <span>{fmtDate(series.at(-1)!.at)}</span>
            </div>
          </Section>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <Section title="环境变量（secret 脱敏）">
              <Table
                size="small"
                rowKey="key"
                pagination={false}
                aria-label="环境变量"
                dataSource={ENV_VARS}
                columns={[
                  {
                    title: '键',
                    dataIndex: 'key',
                    render: (v: string, r) => (
                      <Space size={4}>
                        {r.secret && <LockOutlined style={{ color: 'var(--amber)', fontSize: 11 }} />}
                        <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{v}</Text>
                      </Space>
                    ),
                  },
                  { title: '值', dataIndex: 'value', render: (v: string) => <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{v}</Text> },
                  { title: '作用域', dataIndex: 'scope', width: 70, render: (v: string) => <Tag>{v}</Tag> },
                ]}
              />
              <HintText style={{ marginTop: 8 }}>真实凭证只存在于环境侧（.ai/env 纪律），页面只消费脱敏值。</HintText>
            </Section>

            <Section title="发布历史（最近）">
              <Descriptions size="small" column={1} bordered
                items={[
                  { key: 'path', label: '工程路径', children: <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{app.path}</Text> },
                  { key: 'desc', label: '说明', children: <span style={{ fontSize: 12 }}>{app.description}</span> },
                ]}
              />
              <div style={{ marginTop: 10 }}>
                {releases.map((r) => (
                  <div key={r.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '5px 0', fontSize: 12.5 }}>
                    <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
                    <Text style={{ flex: 1 }} ellipsis>{r.title}</Text>
                    <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>v{r.version} · {r.env}</Text>
                  </div>
                ))}
              </div>
            </Section>
          </div>
        </>
      )}
    </PageShell>
  );
}
