import { useMemo, useState } from 'react';
import { Card, Space, Tag, Typography } from 'antd';
import { CloudServerOutlined, LockOutlined } from '@ant-design/icons';
import { ENV_VARS, envOverview, fmtFull, ENV_ANTD, envLabel } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

/** H1 环境中心：dev/test/prod 三环境概览与单环境详情（当前版本/发布历史/变量脱敏/冻结策略）。 */
export default function EnvCenter() {
  const state = useStore((s) => s.state);
  const envs = useMemo(() => envOverview(state), [state]);
  const [selected, setSelected] = useState<'dev' | 'test' | 'prod'>('prod');
  const current = envs.find((e) => e.env === selected)!;
  const history = state.releases
    .filter((r) => r.env === selected)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, 6);
  const vars = ENV_VARS.filter((v) => v.scope === selected);

  return (
    <PageShell
      title="环境中心"
      domain="环境域 H1"
      desc="dev / test / prod 三环境总览与详情；口径与 .ai/env 三态一致（prod 只读纪律、冻结窗口）。"
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, marginBottom: 16 }}>
        {envs.map((e) => (
          <Card
            key={e.env}
            size="small"
            hoverable
            onClick={() => setSelected(e.env)}
            style={{ borderColor: selected === e.env ? 'var(--accent)' : 'var(--border)' }}
            title={
              <Space size={6}>
                <CloudServerOutlined style={{ color: 'var(--accent)' }} />
                <Text strong>{e.label}</Text>
                <Tag color={ENV_ANTD[e.env]}>{e.env}</Tag>
              </Space>
            }
          >
            <div style={{ fontSize: 22, fontWeight: 700, fontFamily: 'var(--mono)', color: 'var(--accent)', marginBottom: 6 }}>
              {e.deployedVersion ? `v${e.deployedVersion}` : '未部署'}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-2)' }}>
              已发布 {e.publishes} 次 · 进行中 {e.inFlight}
            </div>
            <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 6 }}>
              冻结：{e.freeze}
            </div>
          </Card>
        ))}
      </div>

      <KpiRow>
        <Kpi label="当前部署" value={current.deployedVersion ? `v${current.deployedVersion}` : '—'} tone={current.tone} />
        <Kpi label="累计发布" value={current.publishes} tone="info" />
        <Kpi label="进行中" value={current.inFlight} tone="violet" />
        <Kpi label="变量数（脱敏）" value={current.varCount} tone="muted" />
      </KpiRow>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <Section title={`${envLabel(selected)} · 最近发布历史`}>
          {history.length === 0 ? (
            <HintText>该环境暂无发布记录。</HintText>
          ) : (
            history.map((r) => (
              <div key={r.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
                <Tag color={ENV_ANTD[r.env]}>{r.id}</Tag>
                <Text style={{ fontSize: 12.5, flex: 1 }} ellipsis>{r.title}</Text>
                <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>v{r.version}</Text>
                <Text type="secondary" style={{ fontSize: 11 }}>{fmtFull(r.updatedAt)}</Text>
              </div>
            ))
          )}
        </Section>

        <Section title={`${envLabel(selected)} · 环境变量（secret 脱敏）`}>
          {vars.length === 0 ? (
            <HintText>该环境暂无预置变量（演示数据）。</HintText>
          ) : (
            vars.map((v) => (
              <div key={v.key} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
                {v.secret && <LockOutlined style={{ color: 'var(--amber)', fontSize: 12 }} />}
                <Text style={{ fontFamily: 'var(--mono)', fontSize: 12, flex: 1 }}>{v.key}</Text>
                <Text style={{ fontFamily: 'var(--mono)', fontSize: 12, color: v.secret ? 'var(--amber)' : 'var(--text-2)' }}>{v.value}</Text>
              </div>
            ))
          )}
          <HintText style={{ marginTop: 8 }}>
            真实凭证只在环境侧（.ai/env 纪律）；prod 变更需双人复核并留痕（联动 C3 审计）。
          </HintText>
        </Section>
      </div>
    </PageShell>
  );
}
