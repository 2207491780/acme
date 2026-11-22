import { Graph, type Edge, type Cell } from '@antv/x6';
import { register } from '@antv/x6-react-shape';
import { Selection as SelectionPlugin } from '@antv/x6-plugin-selection';
import { Transform } from '@antv/x6-plugin-transform';
import { Snapline } from '@antv/x6-plugin-snapline';
import { Clipboard } from '@antv/x6-plugin-clipboard';
import { Keyboard } from '@antv/x6-plugin-keyboard';
import { MiniMap } from '@antv/x6-plugin-minimap';
import { TONES, type BpmnModel, type CanvasPalette } from '@acme/core';
import { DEFAULT_NODE_SIZES, autoLayout, importModel, parseLayoutJson } from './designerModel';
import { BpmnNodeView, type BpmnNodeData } from './NodeShape';

/**
 * BPMN 画布共享装配工厂（REQ-20261122-102730 契约⑥）：
 * DesignerPage（交互态）与实例详情 Drawer（readOnly）共用同一装配面——
 * readOnly=true 时不注册 Selection/Transform/Keyboard/Clipboard/MiniMap 交互插件、
 * 不注入连线创建（createEdge）与连线校验，仅保留正交路由渲染与网格/背景主题。
 * 节点形状注册/节点数据整形/边 attrs/模型→图同步在此单源，页面不再各写一份。
 */

/** x6-react-shape 节点形状名（模块级一次注册；组件=React 形态 AC-3）。 */
export const BPMN_NODE_SHAPE = 'acme-bpmn-node';

let shapeRegistered = false;
export function ensureShapeRegistered(): void {
  if (shapeRegistered) return;
  register({ shape: BPMN_NODE_SHAPE, component: BpmnNodeView, width: 128, height: 48 });
  shapeRegistered = true;
}

/** 画布交互回调面（readOnly 忽略全部）。 */
export interface BpmnCanvasHandlers {
  onBlankClick?: () => void;
  onCellClick?: (id: string, isEdge: boolean) => void;
  onNodeMoved?: (id: string, position: { x: number; y: number }) => void;
  /** 新连线落地：返回 'remove' 时工厂移除该临时边（驳回/模型接管回填），否则保留。 */
  onEdgeConnected?: (sourceId: string, targetId: string, edge: Edge) => 'remove' | 'keep';
  /** 连线合法性判定（X6 validateConnection 透传）。 */
  validateConnection?: (sourceId: string, targetId: string) => boolean;
  /** 连线拖拽临时边创建（交互态；X6 需经外层 graph 引用创建）。 */
  createEdge?: () => Edge | null;
}

export interface CreateBpmnCanvasOptions {
  container: HTMLElement;
  /** minimap 容器（readOnly 忽略）。 */
  minimapContainer?: HTMLElement | null;
  palette: CanvasPalette;
  /** 只读态：禁交互插件与连线创建（契约⑥）。 */
  readOnly?: boolean;
  handlers?: BpmnCanvasHandlers;
}

/** 节点渲染数据整形（DesignerPage 与只读渲染共用口径；running=实例活跃节点高亮态）。 */
export function bpmnNodeDataOf(
  node: BpmnModel['nodes'][number],
  palette: CanvasPalette,
  dark: boolean,
  name: string,
  summary?: string,
  flags?: { running?: boolean; error?: boolean },
): BpmnNodeData {
  return {
    type: node.type,
    name,
    summary,
    error: flags?.error === true,
    running: flags?.running === true,
    nodeFill: palette.nodeFill,
    nodeBorder: palette.nodeBorder,
    nodeText: palette.nodeText,
    nodeText2: palette.nodeText2,
    dark,
  };
}

/** 连线渲染 attrs（rejected=REJECT_EDGE 驳回回流边标红，走 TONES.danger 令牌）。 */
export function bpmnEdgeAttrs(palette: CanvasPalette, rejected: boolean): Record<string, unknown> {
  return {
    line: {
      stroke: rejected ? TONES.danger : palette.edge,
      strokeWidth: rejected ? 2 : 1.5,
      targetMarker: { name: 'block', size: 8 },
    },
  };
}

/**
 * 画布装配（挂载一次；readOnly 降级为纯渲染）：
 * 交互态=网格+框选+缩放+对齐+剪贴板+键盘+minimap+连线白名单校验；
 * 只读态=仅正交路由连线渲染，无插件无连线创建。
 */
export function createBpmnCanvas(options: CreateBpmnCanvasOptions): Graph {
  const { container, minimapContainer, palette, readOnly = false, handlers = {} } = options;
  ensureShapeRegistered();
  const graph = new Graph({
    container,
    grid: { visible: true, type: 'dot', size: 20, args: { color: palette.grid, thickness: 1 } },
    background: { color: palette.background },
    connecting: {
      router: 'orth',
      connector: { name: 'rounded', args: { radius: 8 } },
      anchor: 'center',
      connectionPoint: 'anchor',
      allowBlank: false,
      allowLoop: false,
      allowNode: false,
      allowMulti: false,
      // 交互态专属：magnet 吸附高亮 + 连线创建 + 白名单校验（readOnly 禁用，契约⑥）
      ...(readOnly
        ? {}
        : {
            highlight: true,
            createEdge: () => handlers.createEdge?.() ?? null,
            validateConnection: ({ sourceCell, targetCell }: { sourceCell?: Cell | null; targetCell?: Cell | null }) => {
              if (!sourceCell || !targetCell) return false;
              return handlers.validateConnection?.(sourceCell.id, targetCell.id) ?? false;
            },
          }),
    },
    highlighting: readOnly
      ? undefined
      : {
          magnetAdsorbed: { name: 'stroke', args: { attrs: { stroke: palette.selection, strokeWidth: 2 } } },
        },
  });
  if (!readOnly) {
    graph.use(new SelectionPlugin({ enabled: true, multiple: true, rubberband: true, showNodeSelectionBox: false }));
    graph.use(new Transform({ resizing: true, rotating: false }));
    graph.use(new Snapline({ enabled: true }));
    graph.use(new Clipboard({ enabled: true }));
    graph.use(new Keyboard({ enabled: true }));
    if (minimapContainer) {
      graph.use(new MiniMap({ container: minimapContainer, width: 180, height: 120 }));
    }
    graph.on('blank:click', () => handlers.onBlankClick?.());
    graph.on('cell:click', ({ cell }: { cell: Cell }) => {
      handlers.onCellClick?.(cell.id, cell.isEdge());
    });
    graph.on('node:moved', ({ node }: { node: { id: string; getPosition: () => { x: number; y: number } } }) => {
      const position = node.getPosition();
      handlers.onNodeMoved?.(node.id, { x: position.x, y: position.y });
    });
    graph.on('edge:connected', ({ edge, isNew }: { edge: Edge; isNew: boolean }) => {
      if (!isNew) return;
      if (handlers.onEdgeConnected?.(edge.getSourceCellId(), edge.getTargetCellId(), edge) === 'remove') {
        graph.removeCell(edge);
      }
    });
  }
  return graph;
}

/** 主题变化 → 网格/背景刷新（节点配色由各页经 bpmnNodeDataOf 重灌）。 */
export function applyCanvasTheme(graph: Graph, palette: CanvasPalette): void {
  graph.options.grid = { visible: true, type: 'dot', size: 20, args: { color: palette.grid, thickness: 1 } };
  graph.drawGrid();
  graph.drawBackground({ color: palette.background });
}

/** 模型→图同步标签供给（节点名兜底与摘要行组装由页面注入）。 */
export interface ModelSyncLabels {
  nameOf: (node: BpmnModel['nodes'][number]) => string;
  summaryOf: (node: BpmnModel['nodes'][number]) => string | undefined;
}

export interface ModelSyncExtras {
  /** 实例活跃节点（running 态高亮，BPM-3 契约⑥）。 */
  activeNodeIds?: ReadonlySet<string>;
  /** 驳回回流边（REJECT_EDGE 标红）。 */
  rejectEdgeIds?: ReadonlySet<string>;
}

/**
 * 模型 → 图同步（节点增删/数据刷新/连线增删）：
 * 交互态与只读态共用；positions 提供节点坐标（缺省 {0,0} 由 positions 预填兜底）。
 */
export function syncModelToGraph(
  graph: Graph,
  model: BpmnModel,
  positions: Map<string, { x: number; y: number }>,
  palette: CanvasPalette,
  dark: boolean,
  labels: ModelSyncLabels,
  extras: ModelSyncExtras = {},
): void {
  const modelNodeIds = new Set(model.nodes.map((node) => node.id));
  for (const node of graph.getNodes()) {
    if (!modelNodeIds.has(node.id) && node.shape === BPMN_NODE_SHAPE) {
      graph.removeCell(node);
    }
  }
  for (const node of model.nodes) {
    const size = DEFAULT_NODE_SIZES[node.type];
    const position = positions.get(node.id) ?? { x: 0, y: 0 };
    const data = bpmnNodeDataOf(node, palette, dark, labels.nameOf(node), labels.summaryOf(node), {
      running: extras.activeNodeIds?.has(node.id) === true,
    });
    const cell = graph.getCellById(node.id);
    if (!cell) {
      graph.addNode({
        id: node.id,
        shape: BPMN_NODE_SHAPE,
        x: position.x,
        y: position.y,
        width: size.width,
        height: size.height,
        data,
      });
    } else {
      cell.setData(data, { overwrite: true });
    }
  }
  const modelEdgeIds = new Set(model.edges.map((edge) => edge.id));
  for (const edge of graph.getEdges()) {
    if (!modelEdgeIds.has(edge.id)) {
      graph.removeCell(edge);
    }
  }
  for (const edge of model.edges) {
    if (!graph.getCellById(edge.id)) {
      graph.addEdge({
        id: edge.id,
        source: { cell: edge.sourceId },
        target: { cell: edge.targetId },
        attrs: bpmnEdgeAttrs(palette, extras.rejectEdgeIds?.has(edge.id) === true),
      });
    }
  }
}

/** 只读渲染输入（实例详情流程图：定义 XML + 布局 + 高亮面）。 */
export interface ReadOnlyRenderInput {
  bpmnXml: string;
  layoutJson?: string | null;
  activeNodeIds?: ReadonlySet<string>;
  rejectEdgeIds?: ReadonlySet<string>;
}

/**
 * 只读模型渲染（实例详情 Drawer 用）：清空画布 → 解析 XML → 布局定位（layoutJson 优先，
 * 缺省 autoLayout）→ 同步节点/边（activeNodes running 高亮 + REJECT_EDGE 标红）→ 居中。
 */
export function renderReadOnlyBpmn(
  graph: Graph,
  input: ReadOnlyRenderInput,
  palette: CanvasPalette,
  dark: boolean,
  labels: ModelSyncLabels,
): void {
  graph.fromJSON({ cells: [] });
  const model = importModel(input.bpmnXml);
  const layout = parseLayoutJson(input.layoutJson ?? null);
  const positions: Map<string, { x: number; y: number }> = layout
    ? new Map(layout.nodes.map((point) => [point.id, { x: point.x, y: point.y }]))
    : autoLayout(model);
  syncModelToGraph(graph, model, positions, palette, dark, labels, {
    activeNodeIds: input.activeNodeIds,
    rejectEdgeIds: input.rejectEdgeIds,
  });
  const cells = graph.getCells();
  if (cells.length > 0) {
    graph.centerCell(cells[0] as Cell);
  }
}
