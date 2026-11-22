import type { ReactElement } from 'react';
import { FONT_SIZE, FONT_WEIGHT, RADII, SPACING, TONES, statusColor, type BpmnElementType } from '@acme/core';

/**
 * BPMN 节点 React 组件形态（REQ-20261122-023441 AC-3，x6-react-shape 注册用）：
 * 展示=类型徽标+名称+关键属性摘要+校验标红；色值全部走 core 令牌（确认⑦），
 * 主题态经 node data 注入（DesignerPage 在主题变化时刷新 data），组件不感知主题源。
 */

export interface BpmnNodeData {
  type: BpmnElementType;
  name: string;
  /** 关键属性摘要行（UserTask 指派/ServiceTask 引用等，由 DesignerPage 组装）。 */
  summary?: string;
  /** 校验失败标红（AC-9/AC-10 点击定位标红）。 */
  error?: boolean;
  /** 选中框色（X6 selection 插件的面色由配置承载，节点内不再描边）。 */
  nodeFill?: string;
  nodeBorder?: string;
  nodeText?: string;
  nodeText2?: string;
  /** 主题暗/亮态（glyph 取色用）。 */
  dark?: boolean;
}

/** x6-react-shape 组件入参：{ node }；独立渲染（测试）允许省略。 */
interface BpmnNodeViewProps {
  node?: {
    getData: () => unknown;
  } | null;
}

/** 类型徽标（形状语义内联 SVG；色值走令牌派生，禁第二套色）。 */
function TypeGlyph({ type, color }: { type: BpmnElementType; color: string }): ReactElement {
  const common = { fill: 'none', stroke: color, strokeWidth: 1.5 } as const;
  switch (type) {
    case 'StartEvent':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><circle cx="8" cy="8" r="6" {...common} /></svg>;
    case 'EndEvent':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><circle cx="8" cy="8" r="6" fill={color} /></svg>;
    case 'XOR':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><path d="M8 1 15 8 8 15 1 8Z" {...common} /></svg>;
    case 'AND':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><path d="M8 1 15 8 8 15 1 8Z" {...common} /><path d="M8 4v8M4 8h8" {...common} /></svg>;
    case 'ServiceTask':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><rect x="1" y="3" width="14" height="10" rx="2" {...common} /><path d="M4 8h2l1-2 1.6 4L10 8h2" {...common} /></svg>;
    case 'EmbeddedSubProcess':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><rect x="1" y="3" width="14" height="10" rx="2" {...common} /><rect x="3.5" y="6" width="9" height="4" rx="1" {...common} /></svg>;
    case 'BoundaryEvent':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><circle cx="8" cy="8" r="6" {...common} strokeDasharray="2 1.6" /></svg>;
    case 'UserTask':
    default:
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><rect x="1" y="3" width="14" height="10" rx="2" {...common} /><circle cx="4.5" cy="8" r="1.3" fill={color} /></svg>;
  }
}

/** 运行时归一化 node data（外部输入不容断言；缺省值兜底）。 */
function readData(raw: unknown): BpmnNodeData {
  if (typeof raw !== 'object' || raw === null) {
    return { type: 'UserTask', name: '' };
  }
  const candidate = raw as Record<string, unknown>;
  const type = typeof candidate.type === 'string' ? candidate.type : 'UserTask';
  return {
    type: (type === 'StartEvent' || type === 'EndEvent' || type === 'XOR' || type === 'AND'
      || type === 'ServiceTask' || type === 'EmbeddedSubProcess' || type === 'BoundaryEvent'
      ? type : 'UserTask') as BpmnElementType,
    name: typeof candidate.name === 'string' ? candidate.name : '',
    summary: typeof candidate.summary === 'string' && candidate.summary !== '' ? candidate.summary : undefined,
    error: candidate.error === true,
    nodeFill: typeof candidate.nodeFill === 'string' ? candidate.nodeFill : undefined,
    nodeBorder: typeof candidate.nodeBorder === 'string' ? candidate.nodeBorder : undefined,
    nodeText: typeof candidate.nodeText === 'string' ? candidate.nodeText : undefined,
    nodeText2: typeof candidate.nodeText2 === 'string' ? candidate.nodeText2 : undefined,
    dark: candidate.dark === true,
  };
}

export function BpmnNodeView({ node }: BpmnNodeViewProps): ReactElement {
  const data = readData(node?.getData());
  const fill = data.nodeFill ?? 'var(--surface)';
  const border = data.error ? TONES.danger : (data.nodeBorder ?? 'var(--border)');
  const text = data.nodeText ?? 'var(--text)';
  const text2 = data.nodeText2 ?? 'var(--muted)';
  const glyphColor = data.error ? TONES.danger : (data.dark ? text : text2);
  return (
    <div
      data-bpmn-node
      style={{
        width: '100%',
        height: '100%',
        boxSizing: 'border-box',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        gap: 2,
        padding: `${SPACING.xs}px ${SPACING.md}px`,
        background: fill,
        border: `1px solid ${border}`,
        borderRadius: RADII.sm,
        overflow: 'hidden',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: SPACING.xs, minWidth: 0 }}>
        <TypeGlyph type={data.type} color={glyphColor} />
        <span
          style={{
            fontSize: FONT_SIZE.small,
            fontWeight: FONT_WEIGHT.medium,
            color: text,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {data.name || ''}
        </span>
      </div>
      {data.summary ? (
        <span
          style={{
            fontSize: FONT_SIZE.caption,
            color: text2,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {data.summary}
        </span>
      ) : null}
      {data.error ? (
        <span style={{ fontSize: FONT_SIZE.caption, fontWeight: FONT_WEIGHT.semibold, color: statusColor('failed') }}>
          ✕
        </span>
      ) : null}
    </div>
  );
}
