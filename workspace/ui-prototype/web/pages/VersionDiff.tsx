import { useMemo, useState } from 'react';
import { Select, Space, Tag, Typography } from 'antd';
import { releaseDiff } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

const releaseOption = (r: { id: string; title: string; version: string }) => ({
  value: r.id,
  label: `${r.id} · ${r.title}（v${r.version}）`,
});

/** R4 版本对比：两次发布/两个版本的字段级差异视图，变更行高亮。 */
export default function VersionDiff() {
  const state = useStore((s) => s.state);
  const [idA, setIdA] = useState(state.releases[0]?.id ?? '');
  const [idB, setIdB] = useState(state.releases[2]?.id ?? state.releases[1]?.id ?? '');
  const diff = useMemo(() => releaseDiff(state, idA, idB), [state, idA, idB]);
  const changedCount = diff.fields.filter((f) => f.changed).length;

  return (
    <PageShell
      title="版本对比"
      domain="发布工程域 R4"
      desc="选择两条发布单做字段级 diff（标题/版本/环境/平台/负责人/状态/产物/时间线），变更行高亮。"
    >
      <KpiRow>
        <Kpi label="对比字段" value={diff.fields.length} tone="info" />
        <Kpi label="差异字段" value={changedCount} tone={changedCount ? 'warning' : 'success'} />
        <Kpi label="相同字段" value={diff.fields.length - changedCount} tone="muted" />
      </KpiRow>

      <Section
        title={
          <Space size={8} wrap>
            <Select style={{ width: 280 }} value={idA} onChange={setIdA} aria-label="选择对比基准"
              options={state.releases.map(releaseOption)} />
            <Text type="secondary">对比</Text>
            <Select style={{ width: 280 }} value={idB} onChange={setIdB} aria-label="选择对比目标"
              options={state.releases.map(releaseOption)} />
          </Space>
        }
      >
        {!diff.a || !diff.b ? (
          <EmptyState title="请选择两条发布单" />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            {diff.fields.map((f) => (
              <div
                key={f.field}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '90px 1fr 28px 1fr',
                  gap: 10,
                  alignItems: 'center',
                  padding: '8px 10px',
                  borderRadius: 8,
                  background: f.changed ? 'rgba(207,161,79,0.08)' : 'transparent',
                  borderBottom: '1px solid var(--border)',
                }}
              >
                <Text type="secondary" style={{ fontSize: 12 }}>{f.field}</Text>
                <Text style={{ fontSize: 12.5, fontFamily: 'var(--mono)' }}>{f.a}</Text>
                <Text style={{ color: f.changed ? 'var(--amber)' : 'var(--muted)', textAlign: 'center', fontSize: 12 }}>
                  {f.changed ? '≠' : '='}
                </Text>
                <Text style={{ fontSize: 12.5, fontFamily: 'var(--mono)', fontWeight: f.changed ? 700 : 400 }}>{f.b}</Text>
              </div>
            ))}
            <div style={{ marginTop: 10, display: 'flex', gap: 8 }}>
              <Tag color="processing">基准：{diff.a.id}</Tag>
              <Tag color="processing">目标：{diff.b.id}</Tag>
              {changedCount === 0 && <Tag color="success">完全一致</Tag>}
            </div>
          </div>
        )}
      </Section>
      <HintText style={{ marginTop: 12 }}>
        成熟化方向：产物二进制 diff、时间线对齐视图、配置变更扫描（联动 R3 制品库）。
      </HintText>
    </PageShell>
  );
}
