import type { ReactElement } from 'react';
import { FONT_SIZE, FONT_WEIGHT, RADII, SPACING, TONES, statusColor, type BpmnElementType } from '@acme/core';
import { DEFAULT_NODE_SIZES, NODE_SHAPE_HEIGHTS } from './designerModel';

/**
 * BPMN 节点 React 组件形态（REQ-20261122-023441 AC-3，x6-react-shape 注册用）：
 * 展示=类型徽标+名称+关键属性摘要+校验标红；色值全部走 core 令牌（确认⑦），
 * 主题态经 node data 注入（DesignerPage 在主题变化时刷新 data），组件不感知主题源。
 * REQ-20261122-102730（BPM-3 契约⑥）：增 running 态（实例活跃节点高亮，色走 TONES.info 令牌）。
 * REQ-20261124-224523（BPM-2 UI 迭代）：
 * - AC-1 根因修复：Chromium 下 foreignObject 内 `height:100%` 解析为视口高度（实测 40×40 单元格
 *   渲染 40×800 长条）——根 div 宽高改取 node prop 像素值（node.size()，x6-react-shape 传入真实
 *   X6 Node，size()/getSize() 等价），禁用百分比高度；node 为 null（jsdom/独立渲染）时回退
 *   DEFAULT_NODE_SIZES.UserTask 128×48。
 * - AC-2 语义形状（CSS 实现——验收面钉死形态：圆=borderRadius 50%、菱形=旋转 45° 方形、
 *   卡片=圆角矩形、边界事件=虚线描边环）：事件=圆（EndEvent 粗描边、BoundaryEvent 虚线）、
 *   网关=菱形（XOR ×/AND + 标记），名称标签完整显示于图形下方不裁剪；任务/子流程保留卡片式
 *   （徽标+名称+摘要）。x6-react-shape 的 Wrap 监听 node `change:*`（安装包 3.0.1 实证），
 *   change:size 会重渲染本组件，无需额外监听。
 * - delta-r1 修复3（AC-2 标签完整显示）：事件/网关名称标签不再被节点宽度裁剪——根容器与标签区
 *   overflow visible（X6 主题已给 foreignObject overflow:visible，安装包 es/style/raw.js 实证），
 *   标签 span white-space nowrap + flex-shrink 0，去 ellipsis/minWidth 约束，超宽时以 flex 居中
 *   自然左右溢出（BPMN 惯例标签可宽于图形）；任务/子流程卡片内文案保留既有 ellipsis 口径不变。
 */

export interface BpmnNodeData {
  type: BpmnElementType;
  name: string;
  /** 关键属性摘要行（UserTask 指派/ServiceTask 引用等，由 DesignerPage 组装）。 */
  summary?: string;
  /** 校验失败标红（AC-9/AC-10 点击定位标红）。 */
  error?: boolean;
  /** 实例运行中态（BPM-3 实例详情 readOnly 流程图 activeNodes 高亮）。 */
  running?: boolean;
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
    /** X6 Node 尺寸（像素）——根 div 宽高单源，修复 foreignObject 百分比高度解析。 */
    size?: () => { width: number; height: number };
    /** X6 Node 尺寸等价 API（老版别名；真实节点两 API 并存）。 */
    getSize?: () => { width: number; height: number };
  } | null;
}

/** 类型徽标（任务/子流程卡片行内 SVG，仅形状语义；色值走令牌派生，禁第二套色）。 */
function TypeGlyph({ type, color }: { type: BpmnElementType; color: string }): ReactElement {
  const common = { fill: 'none', stroke: color, strokeWidth: 1.5 } as const;
  switch (type) {
    case 'ServiceTask':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><rect x="1" y="3" width="14" height="10" rx="2" {...common} /><path d="M4 8h2l1-2 1.6 4L10 8h2" {...common} /></svg>;
    case 'EmbeddedSubProcess':
      return <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden><rect x="1" y="3" width="14" height="10" rx="2" {...common} /><rect x="3.5" y="6" width="9" height="4" rx="1" {...common} /></svg>;
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
    running: candidate.running === true,
    nodeFill: typeof candidate.nodeFill === 'string' ? candidate.nodeFill : undefined,
    nodeBorder: typeof candidate.nodeBorder === 'string' ? candidate.nodeBorder : undefined,
    nodeText: typeof candidate.nodeText === 'string' ? candidate.nodeText : undefined,
    nodeText2: typeof candidate.nodeText2 === 'string' ? candidate.nodeText2 : undefined,
    dark: candidate.dark === true,
  };
}

/** 事件/网关（语义形状 + 下方标签）与任务/子流程（卡片）的分支判定（canvasFactory ports 装配共用）。 */
export function isSemanticShapeType(type: BpmnElementType): boolean {
  return type === 'StartEvent' || type === 'EndEvent' || type === 'XOR' || type === 'AND' || type === 'BoundaryEvent';
}

/** 网关标记（XOR=×、AND=+）：内部正交双杠，随/抵消外层 45° 旋转得到目标符号。 */
function GatewayMarker({ kind, color }: { kind: 'XOR' | 'AND'; color: string }): ReactElement {
  const bar = (extra: React.CSSProperties): React.CSSProperties => ({
    position: 'absolute',
    background: color,
    ...extra,
  });
  // XOR：标记随菱形一起旋转 45°，正交双杠视觉成 ×；AND：内层反向旋转 45° 抵消，保持 +
  const counterRotate: React.CSSProperties = kind === 'AND' ? { transform: 'rotate(-45deg)' } : {};
  return (
    <div
      style={{
        position: 'relative',
        width: 15,
        height: 15,
        flexShrink: 0,
        ...counterRotate,
      }}
    >
      <span aria-hidden style={bar({ left: 0, top: 6.5, width: 15, height: 2 })} />
      <span aria-hidden style={bar({ left: 6.5, top: 0, width: 2, height: 15 })} />
    </div>
  );
}

export function BpmnNodeView({ node }: BpmnNodeViewProps): ReactElement {
  const data = readData(node?.getData());
  const fill = data.nodeFill ?? 'var(--surface)';
  // running 态=实例活跃节点（BPM-3）：info 色描边；error 校验标红优先级不变
  const border = data.error ? TONES.danger : data.running ? TONES.info : (data.nodeBorder ?? 'var(--border)');
  const text = data.nodeText ?? 'var(--text)';
  const glyphColor = data.error ? TONES.danger : data.running ? TONES.info : (data.dark ? text : data.nodeText2 ?? 'var(--muted)');

  // AC-1 根因修复：宽高取单元格像素值（node.size()），禁百分比高度——foreignObject 内
  // `height:100%` 在 Chromium 解析为视口高度；独立渲染（node 为 null）回退默认尺寸
  const size = node?.size?.() ?? node?.getSize?.() ?? DEFAULT_NODE_SIZES.UserTask;
  const semantic = isSemanticShapeType(data.type);
  const shapeHeight = NODE_SHAPE_HEIGHTS[data.type];

  if (semantic) {
    const labelZone = Math.max(size.height - shapeHeight, 0);
    const isDiamond = data.type === 'XOR' || data.type === 'AND';
    // 菱形=旋转 45° 的内接正方形（对角线=图形区边长）；圆=borderRadius 50%
    const diamondSide = Math.round((Math.min(size.width, shapeHeight) / Math.SQRT2) * 100) / 100;
    const borderWidth = data.type === 'EndEvent' ? 3 : 1.5;
    return (
      <div
        data-bpmn-node
        style={{
          width: size.width,
          height: size.height,
          boxSizing: 'border-box',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          // delta-r1 修复3：不裁剪超宽标签（foreignObject 溢出可见由 X6 主题 CSS 保证）
          overflow: 'visible',
        }}
      >
        {isDiamond ? (
          <div
            style={{
              boxSizing: 'border-box',
              width: diamondSide,
              height: diamondSide,
              marginTop: (shapeHeight - diamondSide) / 2,
              transform: 'rotate(45deg)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: fill,
              border: `1.5px solid ${border}`,
            }}
          >
            <GatewayMarker kind={data.type === 'XOR' ? 'XOR' : 'AND'} color={border} />
          </div>
        ) : (
          <div
            style={{
              boxSizing: 'border-box',
              width: size.width,
              height: shapeHeight,
              borderRadius: '50%',
              background: fill,
              border: `${borderWidth}px ${data.type === 'BoundaryEvent' ? 'dashed' : 'solid'} ${border}`,
              flexShrink: 0,
            }}
          />
        )}
        <div
          style={{
            width: '100%',
            height: labelZone,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: SPACING.xs,
            padding: `0 ${SPACING.xs}px`,
            // delta-r1 修复3：标签区横向自然溢出（不裁剪不换行）
            overflow: 'visible',
          }}
        >
          <span
            style={{
              fontSize: FONT_SIZE.caption,
              fontWeight: FONT_WEIGHT.medium,
              color: text,
              whiteSpace: 'nowrap',
              flexShrink: 0,
            }}
          >
            {data.name || ''}
          </span>
          {data.running ? (
            <span
              aria-hidden
              style={{ width: 6, height: 6, borderRadius: '50%', background: TONES.info, flexShrink: 0 }}
            />
          ) : null}
        </div>
      </div>
    );
  }

  return (
    <div data-bpmn-node style={{ width: size.width, height: size.height, boxSizing: 'border-box' }}>
      <div
        style={{
          width: size.width,
          height: size.height,
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
          {data.running ? (
            <span
              aria-hidden
              style={{ width: 6, height: 6, borderRadius: '50%', background: TONES.info, flexShrink: 0, marginLeft: 'auto' }}
            />
          ) : null}
        </div>
        {data.summary ? (
          <span
            style={{
              fontSize: FONT_SIZE.caption,
              color: data.nodeText2 ?? 'var(--muted)',
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
    </div>
  );
}
