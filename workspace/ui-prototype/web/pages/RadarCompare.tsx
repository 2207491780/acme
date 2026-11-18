import { useMemo } from 'react';
import { Card, Typography } from 'antd';
import { TONES, appRadar, type RadarScore } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

/** 五维雷达（Web 内建 SVG）：吞吐/成功率/交付速度/稳定性/质量门禁。 */
function RadarChart({ data, color }: { data: RadarScore; color: string }) {
  const size = 200;
  const cx = size / 2;
  const cy = size / 2;
  const r = 78;
  const n = data.axes.length;
  const point = (i: number, ratio: number) => {
    const angle = (Math.PI * 2 * i) / n - Math.PI / 2;
    return [cx + Math.cos(angle) * r * ratio, cy + Math.sin(angle) * r * ratio] as const;
  };
  const ring = (ratio: number) =>
    data.axes.map((_, i) => point(i, ratio).join(',')).join(' ');
  const value = data.axes.map((a, i) => point(i, a.score / 100).join(',')).join(' ');

  return (
    <svg width={size} height={size} role="img" aria-label={`${data.appName} 五维雷达图`}>
      {[1 / 3, 2 / 3, 1].map((ratio) => (
        <polygon key={ratio} points={ring(ratio)} fill="none" stroke="var(--border)" strokeWidth="1" />
      ))}
      {data.axes.map((_, i) => {
        const [x, y] = point(i, 1);
        return <line key={i} x1={cx} y1={cy} x2={x} y2={y} stroke="var(--border)" strokeWidth="1" />;
      })}
      <polygon points={value} fill={`${color}33`} stroke={color} strokeWidth="1.6" />
      {data.axes.map((a, i) => {
        const [x, y] = point(i, 1.16);
        return (
          <text key={a.key} x={x} y={y} textAnchor="middle" dominantBaseline="middle" fontSize="10" fill="var(--muted)">
            {a.label}
          </text>
        );
      })}
      {data.axes.map((a, i) => {
        const [x, y] = point(i, a.score / 100);
        return <circle key={a.key} cx={x} cy={y} r="2.4" fill={color} />;
      })}
    </svg>
  );
}

/** V4 应用健康雷达：三端五维对比，全部由正式 store 推导（0-100）。 */
export default function RadarCompare() {
  const state = useStore((s) => s.state);
  const radars = useMemo(() => appRadar(state, Date.now()), [state]);
  const colors = [TONES.info, TONES.violet, TONES.success];

  const best = useMemo(
    () =>
      radars.map((r) => ({
        app: r.appName,
        top: [...r.axes].sort((a, b) => b.score - a.score)[0],
        weak: [...r.axes].sort((a, b) => a.score - b.score)[0],
      })),
    [radars],
  );

  return (
    <PageShell
      title="应用健康雷达"
      domain="可视化看板 V4"
      desc="按应用对比五维能力：交付吞吐 / 成功率 / 交付速度 / 稳定性 / 质量门禁（0-100，正式 store 推导）。"
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16 }}>
        {radars.map((r, i) => (
          <Card key={r.appId} size="small" title={<Text strong>{r.appName}</Text>}>
            <div style={{ display: 'grid', placeItems: 'center' }}>
              <RadarChart data={r} color={colors[i % colors.length]} />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 8 }}>
              {r.axes.map((a) => (
                <div key={a.key} style={{ display: 'grid', gridTemplateColumns: '64px 1fr 34px', gap: 8, alignItems: 'center', fontSize: 12 }}>
                  <Text type="secondary">{a.label}</Text>
                  <div style={{ height: 6, borderRadius: 999, background: 'var(--surface-3)' }}>
                    <div style={{ height: '100%', width: `${a.score}%`, borderRadius: 999, background: colors[i % colors.length] }} />
                  </div>
                  <span style={{ textAlign: 'right', fontFamily: 'var(--mono)' }}>{a.score}</span>
                </div>
              ))}
            </div>
          </Card>
        ))}
      </div>

      <Section title="解读（自动生成）" style={{ marginTop: 16 }}>
        {best.map((b) => (
          <div key={b.app} style={{ padding: '6px 0', fontSize: 13 }}>
            <Text strong>{b.app}</Text>
            <Text type="secondary">：最强项 </Text>
            <Text style={{ color: TONES.success }}>{b.top.label}（{b.top.score}）</Text>
            <Text type="secondary">，待改进 </Text>
            <Text style={{ color: TONES.warning }}>{b.weak.label}（{b.weak.score}）</Text>
          </div>
        ))}
        <HintText style={{ marginTop: 8 }}>
          稳定性维度为确定性演示基线；成熟化时接真实监控（可用率/错误率）。RN 端以横向条近似呈现多边形雷达（idiomatic 取舍，见 UI-SPEC §3）。
        </HintText>
      </Section>
    </PageShell>
  );
}
