import { useMemo } from 'react';
import { Badge, Progress, Space, Tag, Typography } from 'antd';
import { TONES, metrics, publishTrend, releaseFunnel, buildNodes, NODE_STATUS_META } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;
const HEAT_COLORS = ['transparent', 'rgba(111,146,196,0.25)', 'rgba(111,146,196,0.45)', 'rgba(111,146,196,0.7)', 'rgba(111,146,196,1)'];

function fmtTime(iso: string) {
  return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

/** V0 数据大屏：可视化看板中心 —— KPI / 趋势面积图 / 漏斗 / 热力图 / 节点墙 / 构建进度一屏总览。 */
export default function WallDashboard() {
  const state = useStore((s) => s.state);
  const m = useMemo(() => metrics(state), [state]);
  const trend = useMemo(() => publishTrend(state, 14, Date.now()), [state]);
  const funnel = useMemo(() => releaseFunnel(state), [state]);
  const nodes = useMemo(() => buildNodes(state, Date.now(), 24), [state]);
  const building = state.releases.filter((r) => r.status === 'building');
  const heat = useMemo(() => publishTrend(state, 84, Date.now()), [state]);
  const maxTrend = Math.max(1, ...trend.flatMap((p) => [p.created, p.published]));
  const maxFunnel = Math.max(1, ...funnel.map((f) => f.count));
  const successRate = m.total ? Math.round((m.published / m.total) * 100) : 0;

  const areaPath = (key: 'created' | 'published') =>
    trend.map((p, i) => `${i === 0 ? 'M' : 'L'}${(i / (trend.length - 1)) * 100},${40 - (p[key] / maxTrend) * 36}`).join(' ');

  return (
    <PageShell
      title="数据大屏"
      domain="可视化看板 V0"
      desc="看板中心的整合形态：一屏总览趋势、漏斗、热力、节点与进行中构建；数据实时来自正式 store（TICK 驱动）。"
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <Badge status="processing" text={<Text strong>实时</Text>} />
        <Text type="secondary" style={{ fontSize: 12 }}>刷新于 {fmtTime(new Date().toISOString())} · TICK 每 1.2s 推进构建</Text>
        <Space size={4} style={{ marginLeft: 'auto' }}>
          <Tag color="processing">构建 {building.length}</Tag>
          <Tag color="warning">待发布 {m.approved}</Tag>
          <Tag color="error">异常 {m.failed}</Tag>
        </Space>
      </div>

      <KpiRow>
        <Kpi label="发布单总数" value={m.total} tone="info" />
        <Kpi label="累计成功率" value={successRate} unit="%" tone="success" />
        <Kpi label="进行中" value={m.active} tone="violet" />
        <Kpi label="今日发布" value={m.publishedToday} tone="warning" />
      </KpiRow>

      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 16 }}>
        <Section title="创建 / 发布趋势（近 14 天）">
          <svg viewBox="0 0 100 40" preserveAspectRatio="none" style={{ width: '100%', height: 150 }} aria-label="趋势面积图">
            <defs>
              <linearGradient id="wall-area" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={TONES.success} stopOpacity="0.35" />
                <stop offset="100%" stopColor={TONES.success} stopOpacity="0" />
              </linearGradient>
            </defs>
            {[10, 20, 30].map((y) => (
              <line key={y} x1="0" y1={y} x2="100" y2={y} stroke="var(--border)" strokeWidth="0.2" />
            ))}
            <path d={`${areaPath('published')} L100,40 L0,40 Z`} fill="url(#wall-area)" />
            <path d={areaPath('published')} fill="none" stroke={TONES.success} strokeWidth="0.6" vectorEffect="non-scaling-stroke" />
            <path d={areaPath('created')} fill="none" stroke={TONES.info} strokeWidth="0.6" strokeDasharray="2 1.4" vectorEffect="non-scaling-stroke" />
          </svg>
          <Text type="secondary" style={{ fontSize: 11 }}>
            <span style={{ color: TONES.success }}>■ 发布</span>　<span style={{ color: TONES.info }}>┄ 创建</span>
          </Text>
        </Section>

        <Section title="在途漏斗">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {funnel.map((f) => (
              <div key={f.key} style={{ display: 'grid', gridTemplateColumns: '52px 1fr 32px', alignItems: 'center', gap: 8, fontSize: 12 }}>
                <Text type="secondary">{f.label}</Text>
                <div style={{ height: 14, borderRadius: 999, background: 'var(--surface-3)', overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${(f.count / maxFunnel) * 100}%`, background: TONES[f.tone], borderRadius: 999, transition: 'width .4s' }} />
                </div>
                <span style={{ textAlign: 'right', fontFamily: 'var(--mono)', fontWeight: 600 }}>{f.count}</span>
              </div>
            ))}
          </div>
        </Section>

        <Section title="发布热力（近 12 周）">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(12, 1fr)', gap: 3 }}>
            {heat.map((p) => {
              const score = p.created + p.published * 2;
              const level = score === 0 ? 0 : score < 3 ? 1 : score < 6 ? 2 : score < 10 ? 3 : 4;
              return (
                <div
                  key={p.date}
                  title={`${p.date}：建 ${p.created} · 发 ${p.published}`}
                  aria-label={`${p.date} 建 ${p.created} 发 ${p.published}`}
                  style={{ aspectRatio: '1', borderRadius: 3, background: HEAT_COLORS[level], border: '1px solid var(--border)' }}
                />
              );
            })}
          </div>
          <div style={{ display: 'flex', gap: 4, alignItems: 'center', marginTop: 8, fontSize: 11, color: 'var(--muted)' }}>
            低
            {HEAT_COLORS.map((c, i) => (
              <span key={i} style={{ width: 12, height: 12, borderRadius: 3, background: c, border: '1px solid var(--border)', display: 'inline-block' }} />
            ))}
            高
          </div>
        </Section>

        <Section title="节点状态墙（演示集群）">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 6 }}>
            {nodes.map((n) => (
              <div
                key={n.id}
                title={`${n.name} · ${NODE_STATUS_META[n.status].label} · 负载 ${n.load}%${n.job ? ` · ${n.job}` : ''}`}
                aria-label={`节点 ${n.name} ${NODE_STATUS_META[n.status].label}`}
                style={{
                  aspectRatio: '1',
                  borderRadius: 6,
                  border: '1px solid var(--border)',
                  background: `${TONES[NODE_STATUS_META[n.status].tone]}${n.status === 'error' ? '66' : '2e'}`,
                  display: 'grid',
                  placeItems: 'center',
                  fontSize: 9,
                  color: 'var(--text-2)',
                  fontFamily: 'var(--mono)',
                }}
              >
                {n.status === 'error' ? '✕' : n.load}
              </div>
            ))}
          </div>
        </Section>
      </div>

      <Section title="进行中构建" style={{ marginTop: 16 }}>
        {building.length === 0 ? (
          <Text type="secondary" style={{ fontSize: 12 }}>当前没有构建任务。</Text>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12 }}>
            {building.map((r) => (
              <div key={r.id} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 12, background: 'var(--surface-2)' }}>
                <Space wrap size={6} style={{ marginBottom: 6 }}>
                  <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{r.id}</Text>
                  <Text style={{ fontSize: 13 }}>{r.title}</Text>
                </Space>
                <Progress percent={r.progress} size="small" status="active" strokeColor={TONES.info} />
              </div>
            ))}
          </div>
        )}
      </Section>
    </PageShell>
  );
}
