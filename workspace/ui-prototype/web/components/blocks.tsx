import type { CSSProperties, ReactNode } from 'react';
import { Card, Space, Tag, Tooltip, Typography } from 'antd';
import { TONES, type Tone } from '@acme/core';

const { Text, Paragraph } = Typography;

/** 原型页通用区块：让各域页面保持一致的密度与用色（颜色一律来自 core 令牌）。 */

export function Section({
  title,
  extra,
  children,
  style,
}: {
  title: ReactNode;
  extra?: ReactNode;
  children: ReactNode;
  style?: CSSProperties;
}) {
  return (
    <Card
      size="small"
      style={style}
      title={<Text strong style={{ fontSize: 13 }}>{title}</Text>}
      extra={extra}
    >
      {children}
    </Card>
  );
}

export function Kpi({ label, value, unit, tone = 'info', hint }: { label: string; value: ReactNode; unit?: string; tone?: Tone; hint?: string }) {
  return (
    <div
      style={{
        padding: 14,
        borderRadius: 12,
        border: '1px solid var(--border)',
        background: 'var(--surface-2)',
        flex: 1,
        minWidth: 120,
      }}
    >
      <div style={{ fontSize: 12, color: 'var(--muted)' }}>{label}</div>
      <div style={{ fontSize: 24, fontWeight: 700, letterSpacing: '-0.5px', color: TONES[tone], marginTop: 4 }}>
        {value}
        {unit && <span style={{ fontSize: 12, fontWeight: 500, marginLeft: 3 }}>{unit}</span>}
      </div>
      {hint && <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 2 }}>{hint}</div>}
    </div>
  );
}

export function KpiRow({ children }: { children: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>{children}</div>
  );
}

export function BarList({
  items,
  max,
}: {
  items: Array<{ name: string; count: number; tone: Tone; extra?: string }>;
  max?: number;
}) {
  const top = Math.max(1, max ?? Math.max(...items.map((x) => x.count)));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {items.map((item) => (
        <div key={item.name} style={{ display: 'grid', gridTemplateColumns: '150px 1fr 64px', alignItems: 'center', gap: 10, fontSize: 12 }}>
          <Text type="secondary" ellipsis>{item.name}</Text>
          <div style={{ height: 8, borderRadius: 999, background: 'var(--surface-3)', overflow: 'hidden' }}>
            <div
              style={{
                height: '100%',
                borderRadius: 999,
                width: `${(item.count / top) * 100}%`,
                background: TONES[item.tone],
                transition: 'width .4s',
              }}
            />
          </div>
          <span style={{ textAlign: 'right', fontFamily: 'var(--mono)', fontWeight: 600 }}>
            {item.count}
            {item.extra && <span style={{ fontWeight: 400, color: 'var(--muted)' }}> {item.extra}</span>}
          </span>
        </div>
      ))}
    </div>
  );
}

export function MiniLine({
  values,
  color,
  height = 64,
}: {
  values: number[];
  color?: string;
  height?: number;
}) {
  if (values.length < 2) return null;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const w = 100;
  const pts = values
    .map((v, i) => `${(i / (values.length - 1)) * w},${height - ((v - min) / span) * (height - 6) - 3}`)
    .join(' ');
  return (
    <svg viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none" style={{ width: '100%', height }} aria-hidden>
      <polyline points={pts} fill="none" stroke={color ?? TONES.info} strokeWidth={1.6} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

export function ToneTag({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <Tag color={TONES[tone]}>{children}</Tag>;
}

export function LogView({ lines }: { lines: Array<{ offsetSec: number; level: string; stage: string; text: string }> }) {
  const color: Record<string, string> = {
    cmd: TONES.info,
    info: 'var(--text-2)',
    warn: TONES.warning,
    error: TONES.danger,
  };
  return (
    <pre
      className="tree"
      style={{ margin: 0, maxHeight: 420, fontSize: 11.5, lineHeight: 1.7 }}
      aria-label="构建日志"
    >
      {lines.map((l, i) => (
        <div key={i} style={{ display: 'flex', gap: 8, color: color[l.level] }}>
          <span style={{ color: 'var(--muted)', flex: 'none', width: 52 }}>{String(l.offsetSec).padStart(3, '0')}s</span>
          <span style={{ flex: 'none', width: 62, textTransform: 'uppercase', fontSize: 10, paddingTop: 1 }}>{l.stage}</span>
          <span style={{ flex: 1, wordBreak: 'break-all' }}>{l.text}</span>
        </div>
      ))}
      {!lines.length && <span style={{ color: 'var(--muted)' }}>暂无日志</span>}
    </pre>
  );
}

export function HintText({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return (
    <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0, ...style }}>
      {children}
    </Paragraph>
  );
}

export function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0' }}>
      <span style={{ width: 120, flex: 'none', fontSize: 12, color: 'var(--muted)' }}>{label}</span>
      <Space size={8} wrap>
        {children}
      </Space>
    </div>
  );
}

export function ToneDot({ tone }: { tone: Tone }) {
  return (
    <Tooltip title={tone}>
      <span style={{ width: 9, height: 9, borderRadius: '50%', background: TONES[tone], display: 'inline-block' }} />
    </Tooltip>
  );
}
