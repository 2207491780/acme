import { useMemo } from 'react';
import { Segmented, Statistic, Typography } from 'antd';
import { useState } from 'react';
import { cycleTimeStats, failureReasons, metrics, publishTrend, TONES } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { BarList, HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

/** C1 数据报表：发布趋势 / 成功率 / 失败归因 / 阶段耗时，全部由正式 store 数据推导（只读）。 */
export default function Reports() {
  const state = useStore((s) => s.state);
  const [days, setDays] = useState(14);

  const trend = useMemo(() => publishTrend(state, days, Date.now()), [state, days]);
  const reasons = useMemo(() => failureReasons(state), [state]);
  const cycle = useMemo(() => cycleTimeStats(state), [state]);
  const m = useMemo(() => metrics(state), [state]);
  const maxTrend = Math.max(1, ...trend.flatMap((p) => [p.created, p.published]));
  const successRate = m.total ? Math.round((m.published / m.total) * 100) : 0;

  return (
    <PageShell
      title="数据报表"
      domain="观测域 C1"
      desc="发布效率与健康度概览；数据完全由正式 store 推导（只读），不引入独立数据源。"
      extra={
        <Segmented
          value={days}
          onChange={(v) => setDays(v as number)}
          options={[7, 14, 30].map((d) => ({ value: d, label: `${d} 天` }))}
          aria-label="选择统计区间"
        />
      }
    >
      <KpiRow>
        <Kpi label="发布单总数" value={m.total} tone="info" />
        <Kpi label="累计成功率" value={successRate} unit="%" tone="success" hint="published / total" />
        <Kpi label="进行中" value={m.active} tone="violet" />
        <Kpi label="失败待处理" value={m.failed} tone="danger" hint={m.failed ? '建议先看失败归因' : undefined} />
      </KpiRow>

      <Section title={`创建 / 发布趋势（近 ${days} 天）`} style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {trend.map((p) => (
            <div key={p.date} style={{ display: 'grid', gridTemplateColumns: '80px 1fr 130px', alignItems: 'center', gap: 8, fontSize: 11.5 }}>
              <Text type="secondary">{p.date.slice(5).replace('-', '/')}</Text>
              <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                <div style={{ flex: 1, height: 6, background: 'var(--surface-3)', borderRadius: 999, overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${(p.created / maxTrend) * 100}%`, background: TONES.info, borderRadius: 999 }} />
                </div>
                <div style={{ flex: 1, height: 6, background: 'var(--surface-3)', borderRadius: 999, overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${(p.published / maxTrend) * 100}%`, background: TONES.success, borderRadius: 999 }} />
                </div>
              </div>
              <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>
                建 {p.created} · 发 {p.published}
              </Text>
            </div>
          ))}
        </div>
        <HintText style={{ marginTop: 8 }}>
          图例：<span style={{ color: TONES.info }}>■ 创建</span>　<span style={{ color: TONES.success }}>■ 发布</span>
        </HintText>
      </Section>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <Section title="失败归因（按备注关键词口径）">
          {reasons.length ? <BarList items={reasons} /> : <HintText>当前没有失败发布单。</HintText>}
        </Section>
        <Section title="阶段平均耗时（小时）">
          {cycle.length ? <BarList items={cycle} /> : <HintText>尚无已完成发布，暂无法统计。</HintText>}
        </Section>
      </div>

      <Section title="口径说明（原型演示用）" style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
          <Statistic title="成功率" value={successRate} suffix="%" />
          <Statistic title="失败归因样本" value={reasons.reduce((n, x) => n + x.count, 0)} suffix="条" />
          <Statistic title="趋势区间" value={days} suffix="天" />
        </div>
        <HintText style={{ marginTop: 8 }}>
          失败归因基于发布单备注关键词匹配（快照/依赖/环境/其他），成熟化时改为结构化失败原因字段。
        </HintText>
      </Section>
    </PageShell>
  );
}
