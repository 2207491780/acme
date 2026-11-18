import { useMemo } from 'react';
import { Badge, Progress, Space, Tag, Typography } from 'antd';
import { DeploymentUnitOutlined } from '@ant-design/icons';
import { platformShort, statusLabel, statusTone, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section, ToneDot } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** C2 实时大盘：进行中流水线管道 + 构建队列（读取正式 store，TICK 自动刷新，只读）。 */
export default function LiveBoard() {
  const state = useStore((s) => s.state);
  const building = state.releases.filter((r) => r.status === 'building');
  const testing = state.releases.filter((r) => r.status === 'testing');
  const approved = state.releases.filter((r) => r.status === 'approved');
  const queue = useMemo(
    () => state.releases.filter((r) => r.status === 'draft').slice(0, 5),
    [state.releases],
  );

  return (
    <PageShell
      title="实时大盘"
      domain="观测域 C2"
      desc="进行中流水线（构建/测试/待发布）与构建队列一览；数据来自正式 store，TICK 每 1.2s 自动推进构建进度。"
    >
      <KpiRow>
        <Kpi label="构建中" value={building.length} tone="info" />
        <Kpi label="测试中" value={testing.length} tone="violet" />
        <Kpi label="待发布" value={approved.length} tone="warning" />
        <Kpi label="排队（草稿）" value={queue.length} tone="muted" />
      </KpiRow>

      <Section title="流水线 · 构建中">
        {building.length === 0 ? (
          <EmptyState title="当前没有构建" desc="推进一个草稿发布单，构建进度会实时出现在这里。" />
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 12 }}>
            {building.map((r) => (
              <div key={r.id} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 12, background: 'var(--surface-2)' }}>
                <Space wrap size={6} style={{ marginBottom: 8 }}>
                  <Badge status="processing" />
                  <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{r.id}</Text>
                  <Tag color="processing">{platformShort(r.platform)}</Tag>
                  <Text style={{ fontSize: 13 }}>{r.title}</Text>
                </Space>
                <Progress percent={r.progress} strokeColor="var(--accent)" size="small" status="active" />
                <Text type="secondary" style={{ fontSize: 11 }}>v{r.version} · {r.owner}</Text>
              </div>
            ))}
          </div>
        )}
      </Section>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 16 }}>
        <Section title="流水线 · 测试中">
          {testing.length === 0 ? (
            <HintText>空。</HintText>
          ) : (
            testing.map((r) => (
              <div key={r.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0' }}>
                <ToneDot tone="violet" />
                <Text style={{ fontSize: 13, flex: 1 }}>{r.title}</Text>
                <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
              </div>
            ))
          )}
        </Section>
        <Section title="流水线 · 待发布">
          {approved.length === 0 ? (
            <HintText>空。</HintText>
          ) : (
            approved.map((r) => (
              <div key={r.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0' }}>
                <ToneDot tone="warning" />
                <Text style={{ fontSize: 13, flex: 1 }}>{r.title}</Text>
                <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
              </div>
            ))
          )}
        </Section>
      </div>

      <Section title={<Space size={6}><DeploymentUnitOutlined /> 构建队列（先进先出演示口径）</Space>} style={{ marginTop: 16 }}>
        {queue.length === 0 ? (
          <HintText>队列空闲。</HintText>
        ) : (
          queue.map((r, i) => (
            <div key={r.id} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0' }}>
              <span style={{ width: 22, height: 22, borderRadius: '50%', border: '1px solid var(--border)', display: 'grid', placeItems: 'center', fontSize: 11, color: 'var(--muted)' }}>
                {i + 1}
              </span>
              <Text style={{ fontSize: 13, flex: 1 }}>{r.title}</Text>
              <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{r.id}</Text>
            </div>
          ))
        )}
      </Section>
    </PageShell>
  );
}
