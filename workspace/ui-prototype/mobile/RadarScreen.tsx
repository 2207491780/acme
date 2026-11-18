import { useMemo } from 'react';
import { appRadar, type Tone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted } from '../../components/ui';
import { BarList, MonoText, ProtoShell, Section } from './kit';
import { palette } from '../../theme';

const TONES: Tone[] = ['info', 'violet', 'success'];

/** V4 应用健康雷达（APP 形态）：多边形雷达在 RN 无内建 SVG，按 UI-SPEC §3 以横向条近似（idiomatic 取舍）。 */
export default function RadarScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const radars = useMemo(() => appRadar(state, Date.now()), [state]);

  return (
    <ProtoShell
      title="应用健康雷达"
      domain="可视化看板 V4"
      desc="五维评分（0-100）：吞吐 / 成功率 / 交付速度 / 稳定性 / 质量门禁。"
      onBack={onBack}
    >
      {radars.map((r, i) => {
        const tone = TONES[i % TONES.length];
        const sorted = [...r.axes].sort((a, b) => b.score - a.score);
        return (
          <Section key={r.appId} title={r.appName}>
            <BarList items={r.axes.map((a) => ({ name: a.label, count: a.score, tone }))} />
            <MonoText color={palette.muted}>
              最强：{sorted[0].label}（{sorted[0].score}） · 待改进：{sorted.at(-1)!.label}（{sorted.at(-1)!.score}）
            </MonoText>
          </Section>
        );
      })}
      <Muted style={{ fontSize: 11 }}>
        Web 端为 SVG 多边形雷达；RN 无内建 SVG 且不引入未登记依赖，故以横向条同数据近似呈现（语义一致）。
      </Muted>
    </ProtoShell>
  );
}
