import { useMemo } from 'react';
import { Tooltip, Typography } from 'antd';
import { TONES, fmtFull, releaseGantt, type GanttWindow } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

/** V5 版本时间线（甘特）：近 30 天发布单横条，进行中的单延伸至今日标线。 */
export default function GanttTimeline() {
  const state = useStore((s) => s.state);
  const gantt: GanttWindow = useMemo(() => releaseGantt(state, Date.now(), 30, 10), [state]);
  const span = (new Date(gantt.end).getTime() - new Date(gantt.start).getTime()) || 1;

  const pos = (date: string) => ((new Date(date).getTime() - new Date(gantt.start).getTime()) / span) * 100;
  const width = (row: { start: string; end: string }) =>
    Math.max(2, ((new Date(row.end).getTime() - new Date(row.start).getTime()) / span) * 100);

  const apps = useMemo(() => [...new Set(gantt.rows.map((r) => r.appId))], [gantt.rows]);

  return (
    <PageShell
      title="版本时间线"
      domain="可视化看板 V5"
      desc="甘特式横条：每条发布单从创建到最近更新的跨度；进行中/待发布的单延伸至今日（红色竖线）。"
    >
      <Section
        title={
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--muted)', fontWeight: 400 }}>
            <span>{gantt.start}</span>
            <span>{gantt.end}</span>
          </div>
        }
      >
        <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div
            style={{
              position: 'absolute',
              left: `${pos(gantt.today)}%`,
              top: -6,
              bottom: -6,
              width: 2,
              background: TONES.danger,
              borderRadius: 2,
              zIndex: 1,
            }}
            aria-label="今日标线"
          />
          {gantt.rows.map((row) => (
            <div key={row.releaseId} style={{ display: 'grid', gridTemplateColumns: '170px 1fr', alignItems: 'center', gap: 10 }}>
              <Text ellipsis style={{ fontSize: 12 }}>
                <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{row.releaseId}</Text> {row.title}
              </Text>
              <Tooltip title={`${fmtFull(row.start)} → ${fmtFull(row.end)} · ${row.status}`}>
                <div style={{ position: 'relative', height: 16, background: 'var(--surface-2)', borderRadius: 999, border: '1px solid var(--border)' }}>
                  <div
                    style={{
                      position: 'absolute',
                      left: `${pos(row.start)}%`,
                      width: `${width(row)}%`,
                      top: 1,
                      bottom: 1,
                      borderRadius: 999,
                      background: TONES[row.tone],
                      opacity: 0.85,
                    }}
                  />
                </div>
              </Tooltip>
            </div>
          ))}
        </div>
      </Section>

      <div style={{ display: 'flex', gap: 14, marginTop: 14, fontSize: 11, color: 'var(--muted)', flexWrap: 'wrap' }}>
        <span><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 999, background: TONES.success, marginRight: 4 }} />已发布</span>
        <span><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 999, background: TONES.info, marginRight: 4 }} />进行中</span>
        <span><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 999, background: TONES.warning, marginRight: 4 }} />待发布</span>
        <span><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 999, background: TONES.danger, marginRight: 4 }} />失败</span>
        <span style={{ marginLeft: 'auto' }}>窗口 {gantt.start} ~ {gantt.today} · 覆盖应用 {apps.length} 个 · 取优先级前 {gantt.rows.length} 条</span>
      </div>
      <HintText style={{ marginTop: 8 }}>
        成熟化方向：按应用分组泳道、窗口缩放（7/30/90 天）、拖拽调整排期（联动 A4 日历）。
      </HintText>
    </PageShell>
  );
}
