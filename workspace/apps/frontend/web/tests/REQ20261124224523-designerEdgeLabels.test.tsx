/**
 * REQ-20261124-224523 delta-r2 缺陷1——连线标记（条件×/默认斜杠/边名称）在真实 X6 渲染
 * 管线的回归用例（AC-13②③；TL 浏览器走查打回项的 jsdom 固化面）。
 *
 * 根因口径（jsdom + @antv/x6@2.19.2 实证，与本文件此前红绿对照一致）：
 *   EdgeView.updateLabels 每次把静态 Edge.defaultLabel.attrs 深合入每个 label 的 attrs
 *   （es/view/edge.js：ObjectExt.merge({}, defaultLabel.attrs, label.attrs)）；默认 attrs 的
 *   rect 键（含 ref:'label'）经 AttrManager 的 CSS 兜底匹配落到自定义标记的 <rect>（box）上，
 *   其 ref:'label' 在自定义 markup selectors 中无映射 → AttrManager.update 抛
 *   "label reference does not exist"，中断 attrs 应用与 updateLabelPositions——标记裸 DOM、
 *   无 transform，连锁可致同批渲染的后续边整边空。生产修复=syncModelToGraph 为模型边携带
 *   defaultLabel:{attrs:{}} 中和默认 attrs 合并污染（canvasFactory delta-r2 修复1）。
 *
 * 本文件不 mock @antv/x6：真实 Graph 渲染管线（parseJSONMarkup selectors → updateAttrs →
 * updateLabelPositions）。jsdom 缺 SVG 几何 API，由 ./svgGeometryPolyfill 最小补齐
 * （仿射矩阵/路径长度/getBBox；只补环境，不参与断言语义）。标签装配字段与
 * syncModelToGraph 生产形态逐字段一致（attrs/labels/defaultLabel/data）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Graph } from '@antv/x6';
import { resolveCanvasPalette, type BpmnEdgeModel } from '@acme/core';
import { bpmnEdgeAttrs, bpmnEdgeLabels } from '../src/pages/workflow/canvasFactory';
import { installSvgPolyfills } from './svgGeometryPolyfill';

vi.setConfig({ testTimeout: 45000 });

const palette = resolveCanvasPalette('dark');

/** 真实 X6 画布（容器入 DOM；节点用 bare rect——聚焦边标记渲染面，react-shape 节点不在此链路）。 */
async function buildGraph(): Promise<Graph> {
  installSvgPolyfills();
  const container = document.createElement('div');
  container.style.width = '800px';
  container.style.height = '600px';
  document.body.appendChild(container);
  const graph = new Graph({ container, width: 800, height: 600, grid: false });
  graph.addNode({ id: 'n1', shape: 'rect', x: 40, y: 40, width: 100, height: 40 });
  graph.addNode({ id: 'n2', shape: 'rect', x: 300, y: 40, width: 100, height: 40 });
  await new Promise((resolve) => setTimeout(resolve, 30));
  return graph;
}

/** 与 syncModelToGraph 生产形态逐字段一致的模型边装配（attrs/labels/defaultLabel/data）。 */
function addModelEdge(graph: Graph, edge: BpmnEdgeModel): void {
  graph.addEdge({
    id: edge.id,
    source: { cell: edge.sourceId },
    target: { cell: edge.targetId },
    attrs: bpmnEdgeAttrs(palette, false),
    labels: bpmnEdgeLabels(edge, palette),
    defaultLabel: { attrs: {} },
    data: { rejected: false },
  });
}

/** 轮询等待 X6 异步渲染管线产出（scheduler 非同步）。 */
async function waitForRender(predicate: () => boolean, timeoutMs = 5000): Promise<void> {
  const started = Date.now();
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) {
      throw new Error(`渲染等待超时（${timeoutMs}ms）`);
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

function edgeLabelElems(graph: Graph, edgeId: string): Array<SVGGElement> {
  const view = graph.findViewByCell(edgeId) as unknown as { container: SVGGElement } | null;
  if (!view) return [];
  return Array.from(view.container.querySelectorAll<SVGGElement>('.x6-edge-label'));
}

beforeEach(() => {
  installSvgPolyfills();
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('REQ20261124224523 连线标记真实渲染（delta-r2：AC-13②③）', () => {
  it('should_render_condition_mark_with_attrs_and_position_when_edge_has_condition', async () => {
    // 条件流标记（AC-13②）：g.x6-edge-label 内 text 文本=×、rect 有 fill、label 带 transform
    const graph = await buildGraph();
    try {
      addModelEdge(graph, { id: 'flow-1', sourceId: 'n1', targetId: 'n2', condition: '${amount > 1000}' } as BpmnEdgeModel);
      await waitForRender(() => edgeLabelElems(graph, 'flow-1').length > 0);

      const labels = edgeLabelElems(graph, 'flow-1');
      expect(labels.length, '条件流边应渲染 1 个连线标记').toBe(1);
      const label = labels[0];
      const text = label.querySelector('text');
      expect(text?.textContent?.trim(), '条件流标记文本应为 ×').toBe('×');
      const rect = label.querySelector('rect');
      const fill = rect?.getAttribute('fill') ?? '';
      expect(fill.length > 0 && fill !== 'none', `菱形底 rect 应有令牌 fill（实际 ${fill || '无'}）`).toBe(true);
      expect(rect?.getAttribute('transform'), '菱形底 rect 应带 rotate(45) 形态').toContain('rotate');
      expect(label.getAttribute('transform'), '连线标记应带定位 transform（updateLabelPositions 产出）').toBeTruthy();
      // 连锁面回归：attrs 中断曾致整边空渲染——连线本体 path 必须在
      const view = graph.findViewByCell('flow-1') as unknown as { container: SVGGElement };
      expect(view.container.querySelector('path'), '连线本体 path 应随边渲染').toBeTruthy();
    } finally {
      graph.dispose();
    }
  });

  it('should_render_default_slash_mark_when_edge_is_default', async () => {
    // 默认流标记（AC-13②）：线首斜杠 path（d 口径=bpmnEdgeLabels 单源）+ 定位 transform
    const graph = await buildGraph();
    try {
      addModelEdge(graph, { id: 'flow-2', sourceId: 'n1', targetId: 'n2', isDefault: true } as BpmnEdgeModel);
      await waitForRender(() => edgeLabelElems(graph, 'flow-2').length > 0);

      const label = edgeLabelElems(graph, 'flow-2')[0];
      const path = label.querySelector('path');
      expect(path, '默认流标记应渲染斜杠 path').toBeTruthy();
      expect(path?.getAttribute('d'), '斜杠口径应与 bpmnEdgeLabels 单源一致').toBe('M -5 4 L 5 -4');
      expect(path?.getAttribute('fill'), '斜杠 path 不应被填充（fill=none）').toBe('none');
      expect(label.getAttribute('transform'), '连线标记应带定位 transform').toBeTruthy();
    } finally {
      graph.dispose();
    }
  });

  it('should_render_edge_name_text_when_edge_has_name', async () => {
    // 边名称随边显示（AC-13③）：名称文本渲染 + 令牌色 + 定位 transform
    const graph = await buildGraph();
    try {
      addModelEdge(graph, { id: 'flow-3', sourceId: 'n1', targetId: 'n2', name: '同意' } as BpmnEdgeModel);
      await waitForRender(() => edgeLabelElems(graph, 'flow-3').length > 0);

      const label = edgeLabelElems(graph, 'flow-3')[0];
      const text = label.querySelector('text');
      expect(text?.textContent ?? '', '边名称文本应渲染').toContain('同意');
      expect(text?.getAttribute('fill'), '名称色值应走画布令牌（palette.nodeText）').toBe(palette.nodeText);
      expect(label.getAttribute('transform'), '连线标记应带定位 transform').toBeTruthy();
    } finally {
      graph.dispose();
    }
  });

  it('should_refresh_mark_dom_when_existing_edge_labels_replaced', async () => {
    // 既有边更新路径（属性面板防抖回写 → syncModelToGraph setLabels/attr）：
    // 无标记边补填 condition 后标记 DOM 应刷新出现（attrs 应用不再被默认 attrs 合并中断）
    const graph = await buildGraph();
    try {
      addModelEdge(graph, { id: 'flow-4', sourceId: 'n1', targetId: 'n2' } as BpmnEdgeModel);
      await waitForRender(() => (graph.findViewByCell('flow-4') as unknown as { container: SVGGElement } | null) !== null);
      expect(edgeLabelElems(graph, 'flow-4').length, '无 condition/名称/默认语义的边不应有连线标记').toBe(0);

      const cell = graph.getCellById('flow-4');
      const updated: BpmnEdgeModel = { id: 'flow-4', sourceId: 'n1', targetId: 'n2', condition: '${passed}' } as BpmnEdgeModel;
      cell?.attr(bpmnEdgeAttrs(palette, false));
      (cell as unknown as { setLabels: (labels: Array<Record<string, unknown>>) => void })?.setLabels(bpmnEdgeLabels(updated, palette));
      await waitForRender(() => edgeLabelElems(graph, 'flow-4').length > 0);

      const label = edgeLabelElems(graph, 'flow-4')[0];
      expect(label.querySelector('text')?.textContent?.trim(), '更新后条件流标记文本应为 ×').toBe('×');
      expect(label.getAttribute('transform'), '更新后标记应带定位 transform').toBeTruthy();
    } finally {
      graph.dispose();
    }
  });
});
