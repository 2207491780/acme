import { useMemo } from 'react';
import { Typography } from 'antd';
import { releaseFunnel } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

/** V3 发布漏斗：在途五阶段留存 + 相邻阶段转化率（正式 store 只读推导）。 */
export default function FunnelView() {
  const state = useStore((s) => s.state);
  const funnel = useMemo(() => releaseFunnel(state), [state]);
  const max = Math.max(1, ...funnel.map((f) => f.count));
  const failed = state.releases.filter((r) => r.status === 'failed').length;
  const cancelled = state.releases.filter((r) => r.status === 'cancelled').length;
  const entered = funnel[0].count + failed + cancelled; // 进入过漏斗的 ≈ 全部单

  return (
    <PageShell
      title="发布漏斗"
      domain="可视化看板 V3"
      desc="草稿 → 构建 → 测试 → 审批 → 发布 的在途留存；宽度按数量比例，标注相邻转化率。"
    >
      <KpiRow>
        <Kpi label="漏斗内总量" value={funnel.reduce((n, f) => n + f.count, 0)} tone="info" />
        <Kpi label="草稿进入量" value={entered} tone="violet" hint="含失败/取消" />
        <Kpi label="漏斗末态" value={funnel.at(-1)!.count} tone="success" hint="已发布存量" />
        <Kpi label="场外（失败/取消）" value={failed + cancelled} tone="danger" />
      </KpiRow>

      <Section title="阶段留存">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {funnel.map((f, i) => {
            const prev = i === 0 ? null : funnel[i - 1];
            const conv = prev && prev.count > 0 ? Math.round((f.count / prev.count) * 100) : null;
            return (
              <div key={f.key}>
                <div style={{ display: 'flex', justifyContent: 'center' }}>
                  <div
                    style={{
                      width: `${Math.max(14, (f.count / max) * 100)}%`,
                      background: `var(--surface-2)`,
                      border: '1px solid var(--border)',
                      borderRadius: 10,
                      padding: '10px 14px',
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      borderTop: i === 0 ? '3px solid var(--accent)' : undefined,
                    }}
                  >
                    <Text strong>{f.label}</Text>
                    <span style={{ fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 15 }}>{f.count}</span>
                  </div>
                </div>
                {conv !== null && prev && (
                  <div style={{ textAlign: 'center', fontSize: 11, color: 'var(--muted)', marginTop: 2 }}>
                    ↑ 自「{prev.label}」转化 {conv}%
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </Section>

      <Section title="口径说明" style={{ marginTop: 16 }}>
        <HintText>
          漏斗为「当前在途」口径（任一时刻的截面），失败与取消在漏斗场外单独计数；成功率口径见 C1 报表。
          成熟化时可加时间维度（各阶段平均停留时长，数据源同 cycleTimeStats）。
        </HintText>
      </Section>
    </PageShell>
  );
}
