import { useMemo } from 'react';
import { Tag, Typography } from 'antd';
import { deploymentTopology, TONES } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

/** H2 部署拓扑图：应用 → 分发渠道的依赖关系（内建 SVG 连线，桌面依赖 Web 产物）。 */
export default function TopologyMap() {
  const state = useStore((s) => s.state);
  const { nodes, edges } = useMemo(() => deploymentTopology(state), [state]);
  const apps = nodes.filter((n) => n.kind === 'app');
  const channels = nodes.filter((n) => n.kind === 'channel');

  // 三层布局：mobile/web/desktop 横排 → 渠道横排；SVG 直角连线
  const W = 100;
  const layerX = (i: number, count: number) => ((i + 1) / (count + 1)) * W;

  const edgeColor = (label: string) =>
    label.includes('注入') ? TONES.violet : label.includes('静态') ? TONES.info : TONES.success;

  return (
    <PageShell
      title="部署拓扑图"
      domain="环境域 H2"
      desc="系统级部署关系：三端应用与分发渠道的连接（构建产物注入 / 静态产物 / 安装包 / Hermes 包）。"
    >
      <Section title="拓扑关系（当前版本）">
        <div style={{ position: 'relative' }}>
          <svg viewBox="0 0 100 46" preserveAspectRatio="none" style={{ width: '100%', height: 230, position: 'absolute', inset: 0 }} aria-hidden>
            {edges.map((e) => {
              const from = apps.find((n) => n.id === e.from) ?? channels.find((n) => n.id === e.from);
              const to = channels.find((n) => n.id === e.to) ?? apps.find((n) => n.id === e.to);
              if (!from || !to) return null;
              const x1 = from.kind === 'app' ? layerX(apps.indexOf(from), apps.length) : layerX(channels.indexOf(from), channels.length);
              const x2 = to.kind === 'channel' ? layerX(channels.indexOf(to), channels.length) : layerX(apps.indexOf(to), apps.length);
              const y1 = from.kind === 'app' ? 14 : 36;
              const y2 = to.kind === 'channel' ? 36 : 14;
              const mid = (y1 + y2) / 2;
              return (
                <g key={`${e.from}-${e.to}`}>
                  <path d={`M${x1},${y1} L${x1},${mid} L${x2},${mid} L${x2},${y2}`} fill="none" stroke={edgeColor(e.label)} strokeWidth="0.35" vectorEffect="non-scaling-stroke" />
                  <text x={(x1 + x2) / 2} y={mid - 0.8} textAnchor="middle" fontSize="1.8" fill="var(--muted)">{e.label}</text>
                </g>
              );
            })}
          </svg>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, marginBottom: 130 }}>
            {apps.map((n) => (
              <div key={n.id} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 12, background: 'var(--surface-2)', textAlign: 'center' }}>
                <Text strong>{n.label}</Text>
                <div style={{ marginTop: 4 }}>
                  <Tag color="processing">v{n.version}</Tag>
                </div>
                <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 6 }}>应用节点</div>
              </div>
            ))}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
            {channels.map((n) => (
              <div key={n.id} style={{ border: '1px dashed var(--border)', borderRadius: 12, padding: 12, background: 'var(--bg-1)', textAlign: 'center' }}>
                <Text strong style={{ color: 'var(--text-2)' }}>{n.label}</Text>
                <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 6 }}>分发渠道</div>
              </div>
            ))}
          </div>
        </div>
      </Section>

      <Section title="连线说明" style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {edges.map((e) => {
            const from = nodes.find((n) => n.id === e.from)!;
            const to = nodes.find((n) => n.id === e.to)!;
            return (
              <div key={`${e.from}-${e.to}`} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12.5 }}>
                <span style={{ width: 10, height: 10, borderRadius: 3, background: edgeColor(e.label), display: 'inline-block' }} />
                <Text strong>{from.label}</Text>
                <Text type="secondary">→（{e.label}）→</Text>
                <Text strong>{to.label}</Text>
              </div>
            );
          })}
        </div>
        <HintText style={{ marginTop: 10 }}>
          桌面端复用 Web 构建产物（紫色连线）是本工程核心复用关系；成熟化时补每条边的当前部署版本与最近发布时间。
        </HintText>
      </Section>
    </PageShell>
  );
}
