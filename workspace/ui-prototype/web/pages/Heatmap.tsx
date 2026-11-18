import { useMemo, useState } from 'react';
import { Segmented } from 'antd';
import { metrics, publishHeatmap } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const LEVEL_BG = [
  'transparent',
  'rgba(111,146,196,0.22)',
  'rgba(111,146,196,0.42)',
  'rgba(111,146,196,0.68)',
  'rgba(111,146,196,1)',
];
const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

/** V2 发布热力图：GitHub 风格 12×7 活动格，颜色随 当日创建+发布 强度加深。 */
export default function Heatmap() {
  const state = useStore((s) => s.state);
  const [weeks, setWeeks] = useState<12 | 26>(12);
  const cells = useMemo(() => publishHeatmap(state, weeks, Date.now()), [state, weeks]);
  const m = useMemo(() => metrics(state), [state]);
  const activeDays = cells.filter((c) => c.level > 0).length;
  const busiest = [...cells].sort((a, b) => b.created + b.published * 2 - (a.created + a.published * 2))[0];

  return (
    <PageShell
      title="发布热力图"
      domain="可视化看板 V2"
      desc="按日聚合的发布活动强度：一格一天，颜色越深活动越多（活动分 = 创建 + 发布×2）；悬停查看明细。"
      extra={
        <Segmented
          value={weeks}
          onChange={(v) => setWeeks(v as 12 | 26)}
          options={[{ value: 12, label: '12 周' }, { value: 26, label: '26 周' }]}
          aria-label="切换热力图跨度"
        />
      }
    >
      <KpiRow>
        <Kpi label="活跃天数" value={`${activeDays}/${cells.length}`} tone="info" />
        <Kpi label="最活跃一天" value={busiest?.date.slice(5) ?? '—'} tone="success" hint={busiest ? `建 ${busiest.created} · 发 ${busiest.published}` : undefined} />
        <Kpi label="累计发布" value={m.published} tone="violet" />
        <Kpi label="累计成功率" value={`${m.total ? Math.round((m.published / m.total) * 100) : 0}%`} tone="warning" />
      </KpiRow>

      <Section title={`活动格（${weeks} 周 × 7 天）`}>
        <div style={{ display: 'flex', gap: 6 }}>
          <div style={{ display: 'grid', gridTemplateRows: 'repeat(7, 14px)', gap: 3, fontSize: 10, color: 'var(--muted)' }}>
            {WEEK_LABELS.map((w) => (
              <span key={w} style={{ lineHeight: '14px' }}>{w}</span>
            ))}
          </div>
          <div style={{ display: 'grid', gridTemplateRows: 'repeat(7, 14px)', gridAutoFlow: 'column', gridAutoColumns: '14px', gap: 3, flex: 1 }}>
            {cells.map((c) => (
              <div
                key={c.date}
                title={`${c.date}：建 ${c.created} · 发 ${c.published}`}
                aria-label={`${c.date} 建 ${c.created} 发 ${c.published}`}
                style={{ width: 14, height: 14, borderRadius: 3, background: LEVEL_BG[c.level], border: '1px solid var(--border)' }}
              />
            ))}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 4, alignItems: 'center', marginTop: 12, fontSize: 11, color: 'var(--muted)' }}>
          低
          {LEVEL_BG.map((c, i) => (
            <span key={i} style={{ width: 12, height: 12, borderRadius: 3, background: c, border: '1px solid var(--border)', display: 'inline-block' }} />
          ))}
          高
          <span style={{ marginLeft: 12 }}>首日 {cells[0]?.date} · 末日 {cells.at(-1)?.date}</span>
        </div>
      </Section>

      <Section title="口径与成熟化方向" style={{ marginTop: 16 }}>
        <HintText>
          强度阈值（1/3/6/10 分）为原型演示口径；成熟化时按团队节奏自适应分位，并支持点击格子下钻当日发布单列表（联动 A3 详情）。
        </HintText>
      </Section>
    </PageShell>
  );
}
