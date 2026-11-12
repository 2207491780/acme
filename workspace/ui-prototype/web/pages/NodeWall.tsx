import { useMemo, useState } from 'react';
import { Card, Segmented, Typography } from 'antd';
import { buildNodes, NODE_STATUS_META, type NodeStatus } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;
const STATUS_BG: Record<NodeStatus, string> = {
  healthy: 'rgba(111,162,135,0.30)',
  building: 'rgba(111,146,196,0.30)',
  idle: 'rgba(139,146,158,0.18)',
  error: 'rgba(196,123,123,0.45)',
};
const STATUS_ORDER: NodeStatus[] = ['building', 'healthy', 'idle', 'error'];

/** V6 节点状态墙：构建集群一览（确定性演示），点击节点查看详情。 */
export default function NodeWall() {
  const state = useStore((s) => s.state);
  const [size, setSize] = useState<24 | 48>(24);
  const [selected, setSelected] = useState<string | null>(null);
  const nodes = useMemo(() => buildNodes(state, Date.now(), size), [state, size]);
  const counts = useMemo(() => {
    const map = new Map<NodeStatus, number>();
    for (const n of nodes) map.set(n.status, (map.get(n.status) ?? 0) + 1);
    return map;
  }, [nodes]);
  const current = nodes.find((n) => n.id === selected);

  return (
    <PageShell
      title="节点状态墙"
      domain="可视化看板 V6"
      desc="构建集群的格子墙：颜色 = 状态，数字 = 负载；构建中的节点挂着当前发布单（TICK 同源）。"
      extra={
        <Segmented
          value={size}
          onChange={(v) => setSize(v as 24 | 48)}
          options={[{ value: 24, label: '24 节点' }, { value: 48, label: '48 节点' }]}
          aria-label="切换节点数量"
        />
      }
    >
      <KpiRow>
        {STATUS_ORDER.map((s) => (
          <Kpi key={s} label={NODE_STATUS_META[s].label} value={counts.get(s) ?? 0} tone={NODE_STATUS_META[s].tone} />
        ))}
      </KpiRow>

      <Section title={`节点（${nodes.length}）`}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(72px, 1fr))', gap: 8 }}>
          {nodes.map((n) => (
            <button
              key={n.id}
              type="button"
              onClick={() => setSelected(n.id)}
              aria-label={`节点 ${n.name}，${NODE_STATUS_META[n.status].label}，负载 ${n.load}%`}
              title={`${n.name} · ${NODE_STATUS_META[n.status].label}${n.job ? ` · ${n.job}` : ''}`}
              style={{
                aspectRatio: '1',
                borderRadius: 8,
                border: selected === n.id ? '2px solid var(--accent)' : '1px solid var(--border)',
                background: STATUS_BG[n.status],
                color: 'var(--text-2)',
                fontFamily: 'var(--mono)',
                fontSize: 11,
                cursor: 'pointer',
                display: 'grid',
                placeItems: 'center',
                gap: 2,
              }}
            >
              <span style={{ fontSize: n.status === 'error' ? 15 : 13, fontWeight: 700 }}>
                {n.status === 'error' ? '✕' : `${n.load}`}
              </span>
              <span style={{ fontSize: 9 }}>{n.name}</span>
            </button>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 14, marginTop: 12, fontSize: 11, color: 'var(--muted)', flexWrap: 'wrap' }}>
          {STATUS_ORDER.map((s) => (
            <span key={s}>
              <span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 3, background: STATUS_BG[s], border: '1px solid var(--border)', marginRight: 4 }} />
              {NODE_STATUS_META[s].label}
            </span>
          ))}
        </div>
      </Section>

      {current && (
        <Card size="small" style={{ marginTop: 16 }} title={<Text strong>节点详情 · {current.name}</Text>}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10, fontSize: 12 }}>
            <span>状态：<Text strong>{NODE_STATUS_META[current.status].label}</Text></span>
            <span>负载：<Text strong style={{ fontFamily: 'var(--mono)' }}>{current.load}%</Text></span>
            <span>当前任务：<Text strong style={{ fontFamily: 'var(--mono)' }}>{current.job ?? '—'}</Text></span>
            <span>节点 ID：<Text style={{ fontFamily: 'var(--mono)' }}>{current.id}</Text></span>
          </div>
          <HintText style={{ marginTop: 8 }}>成熟化时接入真实构建集群心跳与任务分配记录。</HintText>
        </Card>
      )}
    </PageShell>
  );
}
