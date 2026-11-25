import { Graph, Point, Registry, type Cell, type Edge, type Node } from '@antv/x6';
import { register } from '@antv/x6-react-shape';
import { Selection as SelectionPlugin } from '@antv/x6-plugin-selection';
import { Snapline } from '@antv/x6-plugin-snapline';
import { Clipboard } from '@antv/x6-plugin-clipboard';
import { Keyboard } from '@antv/x6-plugin-keyboard';
import { MiniMap } from '@antv/x6-plugin-minimap';
import { FONT_SIZE, TONES, type BpmnEdgeModel, type BpmnElementType, type BpmnModel, type CanvasPalette } from '@acme/core';
import { DEFAULT_NODE_SIZES, NODE_SHAPE_HEIGHTS, autoLayout, importModel, parseLayoutJson } from './designerModel';
import { BpmnNodeView, isSemanticShapeType, type BpmnNodeData } from './NodeShape';

/**
 * BPMN 画布共享装配工厂（REQ-20261122-102730 契约⑥）：
 * DesignerPage（交互态）与实例详情 Drawer（readOnly）共用同一装配面——
 * readOnly=true 时不注册 Selection/Keyboard/Clipboard/MiniMap/Snapline 交互插件、
 * 不启用平移、不注入连线创建（createEdge）与连线校验，仅保留正交路由渲染与网格/背景主题。
 * 节点形状注册/节点数据整形/边 attrs+labels/模型→图同步在此单源，页面不再各写一份。
 *
 * REQ-20261124-224523（BPM-2 UI 迭代，AC-3/5/13/14）：
 * - Transform 自由 resize 收口（依赖清单不动，仅移除使用，尺寸单源 DEFAULT_NODE_SIZES）；
 * - 连线锚点=自定义图形几何中心 anchor（补偿事件/网关标签区导致的锚点下移）+
 *   connectionPoint 'boundary'（端点止于节点边界，箭头不没入节点）；
 * - 条件流×/默认流斜杠/边名称随边显示（labels）；边 hover/选中高亮走 selection 令牌；
 * - Keyboard 绑定 Delete/Backspace 删除选中节点/边（仅交互态）；
 * - 画布平移=内置 panning manager（@antv/x6@2.19.2 实证存在，blank:mousedown→translateBy，
 *   零新依赖；契约确认④「X6 v2 无内置 pan 选项」前提与安装包不符，上报修正）；框选降级
 *   Shift+拖拽（Selection modifiers:'shift'，安装包 2.2.2 无 rubberbandModifiers 选项名，
 *   modifiers 即框选修饰键闸门，无修饰键拖拽经 allowGraphPanning 协作让位平移）。
 *
 * REQ-20261124-224523 打回单 delta-r1（四项修复，验收=TL 浏览器复验）：
 * - 修复1（P0）：连线不可创建根因=react-shape 注册面无 magnet 注入、connecting.allowNode=false，
 *   节点无可起线锚点（X6 validateConnection 源码实证：allowNode 判定仅对 magnet==null 生效，
 *   port 磁铁豁免）。同步给 BPMN 节点装配四向 ports（top/right/bottom/left，markup circle
 *   r=6 热区、magnet=true；事件/网关锚点按图形几何补偿、任务/子流程四边中点），交互起手=port；
 *   渲染端点仍由 anchor+connectionPoint 决定（BpmnEdgeModel 无 port 概念，addEdge 只传 cell）；
 *   readOnly 渲染不带 ports（extras.ports=false 经 renderReadOnlyBpmn 关闭）。
 * - 修复2（P0）：自定义 anchor 坐标系错误——内置 node-center 返回画布绝对坐标（Node.getBBox
 *   =position+size 实证），原实现返回相对节点左上角坐标被当绝对坐标解释，种子 9002 三条边
 *   悬空画布原点。改为 node.getBBox() 绝对 bbox + NODE_SHAPE_HEIGHTS 补偿图形中心。
 * - 修复3（P1）：事件/网关标签裁剪改在 NodeShape.tsx（overflow visible + nowrap 不截断）。
 * - 修复4（P2）：Graph options 加 autoResize（delta-r1 装配为画布容器本身；delta-r2 修正——
 *   sensor 绑容器自身时 X6 transform.resize 每次写 container.style.width/height 钉死读数，
 *   sensor 永远读不到变化→死锁，故 sensor 目标改为外部传入的专用包裹层，缺省仍为容器）。
 *
 * REQ-20261124-224523 打回单 delta-r2（两项修复，验收=TL 浏览器复验）：
 * - 修复1（P1）：连线标记未渲染（条件×/默认斜杠/边名称）根因=jsdom+真实 X6@2.19.2 实证——
 *   EdgeView.updateLabels 每次把 Edge.defaultLabel.attrs 合入 label attrs（edge.js：
 *   ObjectExt.merge({}, defaultLabel.attrs, label.attrs)），默认 attrs 的 rect 键（含 ref:'label'）
 *   经 AttrManager CSS 兜底匹配落到自定义标记的 <rect>（box）上，其 ref:'label' 在自定义
 *   selectors 中无映射→AttrManager.update 抛 "label reference does not exist"，中断 attrs
 *   应用与 updateLabelPositions（裸 rect/text、无 transform；连锁可致同批渲染的后续边整边空）。
 *   修复=模型边元数据携带 defaultLabel:{attrs:{}} 中和默认 attrs 合并污染；label markup 维持
 *   JSON 数组自带 selector 形态（TL 修复方向②），attrs 键与 selector 一致。
 * - 修复2（P1）：autoResize 死锁——sensor 改绑专用画布包裹层（仅含画布；校验条为其兄弟节点）。
 *   反馈环消除依据：X6 transform.resize 写的是画布容器（包裹层的子元素）内联尺寸，包裹层盒尺寸
 *   只由外部 flex 布局决定（画布容器 position:absolute 脱流），传感器读数不受 resize 输出影响；
 *   校验条在包裹层外，其流内高度变化不参与传感器闭环（r0 弃用布尔 true 的父列污染根因同此理）。
 */

/** x6-react-shape 节点形状名（验收面钉死注册名；组件=React 形态 AC-3）。 */
export const BPMN_NODE_SHAPE = 'acme-bpmn-node';

/** 自定义节点锚点名：图形几何中心（事件/网关=图形区中心，任务=整格中心）。 */
export const SHAPE_CENTER_ANCHOR = 'acme-bpmn-shape-center';

/** BPMN 四向连线锚点组名与方向（port group single，directions 决定 items 顺序）。 */
const BPMN_PORT_GROUP = 'fourWay';

const BPMN_PORT_DIRECTIONS = ['top', 'right', 'bottom', 'left'] as const;

/**
 * BPMN 节点四向连线锚点 ports（delta-r1 修复1）：交互态节点必须携带可起线锚点——
 * react-shape 注册面无 magnet 注入且 connecting.allowNode=false（X6 validateConnection 源码
 * 实证 allowNode 判定仅对 magnet==null 生效，port 磁铁豁免），port 是唯一起线路径。
 * 位置按图形几何：事件/网关锚点落在图形（圆/菱形）几何边缘四向点（NODE_SHAPE_HEIGHTS
 * 补偿图形中心）；任务/子流程=卡片四边中点。热区 circle r=6（≥5）+ magnet=true；
 * 视觉弱化/节点 hover 显影色值全走 global.css 变量层（.bpmn-canvas 作用域），此处不硬编码色。
 */
export function bpmnPortsOf(type: BpmnElementType): NonNullable<Node.Properties['ports']> {
  const size = DEFAULT_NODE_SIZES[type];
  const centerX = size.width / 2;
  // 事件/网关：圆/菱形内接于图形区（圆心=图形区中心），四向锚点=图形几何边缘点；
  // 任务/子流程：卡片四边中点。两种口径均以 NODE_SHAPE_HEIGHTS 单源推导
  const halfGraphic = NODE_SHAPE_HEIGHTS[type] / 2;
  const centerY = halfGraphic;
  const points = isSemanticShapeType(type)
    ? [
        { x: centerX, y: centerY - halfGraphic }, // 图形顶点
        { x: centerX + halfGraphic, y: centerY }, // 图形右点
        { x: centerX, y: centerY + halfGraphic }, // 图形底点（标签区上沿，不进标签区）
        { x: centerX - halfGraphic, y: centerY }, // 图形左点
      ]
    : [
        { x: centerX, y: 0 }, // 卡片上边中点
        { x: size.width, y: size.height / 2 }, // 右边中点
        { x: centerX, y: size.height }, // 下边中点
        { x: 0, y: size.height / 2 }, // 左边中点
      ];
  return {
    groups: {
      [BPMN_PORT_GROUP]: {
        position: { name: 'absolute' },
        markup: [{ tagName: 'circle', selector: 'portBody' }],
        attrs: {
          portBody: {
            // magnet=true：从 port 起手拖拽创建连线（X6 事件委托 mousedown [magnet] 链路）；
            // pointer-events all：即使 fill:none 也保持可命中热区；色值/显隐走 global.css 变量层
            magnet: true,
            r: 6,
            fill: 'none',
            stroke: 'none',
            pointerEvents: 'all',
          },
        },
      },
    },
    items: BPMN_PORT_DIRECTIONS.map((direction, index) => ({
      id: `port-${direction}`,
      group: BPMN_PORT_GROUP,
      args: points[index],
    })),
  };
}

/**
 * 注册节点形状与自定义锚点（幂等可重复；X6 registerNode/registerAnchor 均以 overwrite=true
 * 覆盖同名项，重复注册安全——验收面要求每次 createBpmnCanvas 后注册可观测，不做模块级单例门）。
 */
export function ensureShapeRegistered(): void {
  register({ shape: BPMN_NODE_SHAPE, component: BpmnNodeView, width: 128, height: 48 });
  // 锚点补偿（AC-13①）：cell 含标签区后默认 bbox 中心下移进标签区——改取图形几何中心，
  // 连线起止于图形边缘而非标签区；未知类型回退整格中心（=任务/子流程口径）。
  // delta-r1 修复2：X6 把 anchor 回调返回值当作画布绝对坐标（内置 node-center 即
  // view.cell.getConnectionPoint=绝对 bbox 中心，es/registry/node-anchor/node-center.js 实证），
  // 因此必须用 node.getBBox()（=position+size，绝对坐标）推导，相对坐标会让锚点落在画布原点附近。
  Graph.registerAnchor(
    SHAPE_CENTER_ANCHOR,
    (view) => {
      const node = view.cell;
      if (node.isNode()) {
        const size = node.getSize();
        const bbox = node.getBBox();
        const rawType = (node.getData() as { type?: string } | undefined)?.type;
        const type = (typeof rawType === 'string' && rawType in NODE_SHAPE_HEIGHTS ? rawType : 'UserTask') as keyof typeof NODE_SHAPE_HEIGHTS;
        return new Point(bbox.x + size.width / 2, bbox.y + NODE_SHAPE_HEIGHTS[type] / 2);
      }
      return new Point(0, 0);
    },
    true,
  );
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
  /** Delete/Backspace 删除选中节点/边（AC-13④；模型删除与画布移除由页面经模型同步收敛）。 */
  onDeleteSelected?: (ids: string[]) => void;
}

export interface CreateBpmnCanvasOptions {
  container: HTMLElement;
  /**
   * autoResize 传感器目标（delta-r2 修复2）：必须传「仅含画布容器」的专用包裹层，且校验条
   * 等流内元素不得置于其中（防 resize 反馈环）；缺省退回画布容器自身（只读 Drawer 装配面，
   * 交互态页面必须传包裹层——sensor 绑容器自身时 resize 输出钉死读数，结构性死锁）。
   */
  autoResizeTarget?: HTMLElement;
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
export function bpmnEdgeAttrs(palette: CanvasPalette, rejected: boolean): Registry.Attr.CellAttrs {
  return {
    line: {
      stroke: rejected ? TONES.danger : palette.edge,
      strokeWidth: rejected ? 2 : 1.5,
      targetMarker: { name: 'block', size: 8 },
    },
  };
}

/** 连线基础样式（hover/选中恢复用；rejected 语义优先于主题色）。 */
function baseEdgeStroke(palette: CanvasPalette, rejected: boolean): string {
  return rejected ? TONES.danger : palette.edge;
}

/**
 * 连线画布标记（AC-13②，BPMN 惯例；色值走令牌）：
 * - condition 非空 → 条件流标记（线首小菱形内 ×）；
 * - isDefault → 默认流标记（线首斜杠）；
 * - name → 边名称随边显示（线中，线上方）。
 *
 * delta-r2 修复1：markup 维持 JSON 数组自带 selector 形态，attrs 键与 selector 一一对应；
 * 配套要求=模型边创建时携带 defaultLabel:{attrs:{}}（见 syncModelToGraph）——否则 X6 静态
 * Edge.defaultLabel.attrs（text/rect 键，rect 含 ref:'label'）经 AttrManager CSS 兜底匹配
 * 落到自定义标记元素上，ref 解析失败抛错中断 attrs 应用与定位（连线标记丢失的实证根因）。
 */
export function bpmnEdgeLabels(edge: BpmnEdgeModel, palette: CanvasPalette): Array<Record<string, unknown>> {
  const labels: Array<Record<string, unknown>> = [];
  if (edge.name) {
    labels.push({
      markup: [{ tagName: 'text', selector: 'label' }],
      attrs: {
        label: {
          text: edge.name,
          fill: palette.nodeText,
          fontSize: FONT_SIZE.caption,
          textAnchor: 'middle',
          textVerticalAnchor: 'middle',
        },
      },
      position: { distance: 0.5, offset: { y: -10 } },
    });
  }
  if (edge.condition && edge.condition.trim() !== '') {
    labels.push({
      markup: [{ tagName: 'rect', selector: 'box' }, { tagName: 'text', selector: 'cross' }],
      attrs: {
        box: {
          x: -5,
          y: -5,
          width: 10,
          height: 10,
          fill: palette.nodeFill,
          stroke: palette.edge,
          strokeWidth: 1,
          transform: 'rotate(45)',
        },
        cross: { text: '×', x: 0, y: 3.5, fill: palette.edge, fontSize: 10, textAnchor: 'middle' },
      },
      position: { distance: 0.12 },
    });
  }
  if (edge.isDefault === true) {
    labels.push({
      markup: [{ tagName: 'path', selector: 'slash' }],
      attrs: {
        slash: { d: 'M -5 4 L 5 -4', stroke: palette.edge, strokeWidth: 2, fill: 'none', strokeLinecap: 'round' },
      },
      position: { distance: 0.08 },
    });
  }
  return labels;
}

/** 每图当前 palette（主题切换经 applyCanvasTheme 刷新；边 hover/选中高亮取色用）。 */
const paletteByGraph = new WeakMap<Graph, CanvasPalette>();

/** 恢复连线基础样式（hover 离开/取消选中；rejected 标红语义保留）。 */
function restoreEdgeStroke(edge: Edge, palette: CanvasPalette): void {
  const rejected = (edge.getData() as { rejected?: boolean } | undefined)?.rejected === true;
  edge.attr('line/stroke', baseEdgeStroke(palette, rejected));
  edge.attr('line/strokeWidth', rejected ? 2 : 1.5);
}

/**
 * 画布装配（挂载一次；readOnly 降级为纯渲染）：
 * 交互态=网格+平移+Shift框选+对齐+剪贴板+键盘+minimap+连线白名单校验；
 * 只读态=仅正交路由连线渲染，无插件无连线创建无平移。
 */
export function createBpmnCanvas(options: CreateBpmnCanvasOptions): Graph {
  const { container, minimapContainer, palette, readOnly = false, handlers = {} } = options;
  ensureShapeRegistered();
  const graph = new Graph({
    container,
    // delta-r1 修复4 + delta-r2 修复2：autoResize=传感器目标（外部传入的专用画布包裹层；
    // 勿用布尔 true——源码实证布尔 true 时 sensor 绑 graph.container.parentElement，会把含
    // 校验面板的父列高度算进去造成污染；绑画布容器自身时 transform.resize 每次写容器内联
    // 宽高钉死 offsetWidth 读数→死锁。包裹层=仅含画布，校验条等流内元素不得置于其中）。
    autoResize: options.autoResizeTarget ?? container,
    grid: { visible: true, type: 'dot', size: 20, args: { color: palette.grid, thickness: 1 } },
    background: { color: palette.background },
    // AC-14 画布平移：内置 panning manager（blank:mousedown → translateBy，零新依赖）。
    // 契约确认④自实现路线的前提「X6 v2 无内置 pan 选项」与安装包不符（@antv/x6@2.19.2 实证
    // 内置 panning，es/graph/panning.js），且验收面钉 panning 装配——采用内置；
    // Shift+拖拽时 Selection 的 allowGraphPanning 协作自动让位框选（插件源码实证）。readOnly 不启用。
    panning: readOnly ? false : { enabled: true, eventTypes: ['leftMouseDown', 'mouseWheelDown'] },
    connecting: {
      router: 'orth',
      connector: { name: 'rounded', args: { radius: 8 } },
      // AC-13①：锚点=图形几何中心（自定义 anchor 补偿标签区下移）+ 端点止于节点边界
      anchor: { name: SHAPE_CENTER_ANCHOR },
      connectionPoint: 'boundary',
      allowBlank: false,
      allowLoop: false,
      allowNode: false,
      // delta-r1 修复1：连线起手=port 磁铁（X6 默认 allowPort:true，此处显式钉死；
      // allowNode=false 只挡 node 终端，X6 源码实证 port 磁铁豁免 allowNode 判定）
      allowPort: true,
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
  paletteByGraph.set(graph, palette);
  if (!readOnly) {
    // AC-14 框选降级：Shift+拖拽框选（modifiers 即框选修饰键闸门；无修饰键的空白拖拽
    // 经 Selection 的 allowGraphPanning 协作让位给平移——插件源码实证）。Ctrl/点击多选沿用默认 multipleSelectionModifiers。
    graph.use(new SelectionPlugin({ enabled: true, multiple: true, rubberband: true, modifiers: 'shift', showNodeSelectionBox: false }));
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
    // AC-13④：Delete/Backspace 删除选中节点/边（模型删除经 handlers 收敛，画布随模型同步移除）
    graph.bindKey(['delete', 'backspace'], () => {
      // getSelectedCells 由 selection 插件注入；插件类型增强路径随打包器解析有差异，此处保守取面
      const selected = (graph as unknown as { getSelectedCells?: () => Cell[] }).getSelectedCells?.() ?? [];
      const ids = selected.map((cell) => cell.id);
      if (ids.length > 0) {
        handlers.onDeleteSelected?.(ids);
      }
    });
  }
  // 边 hover/选中高亮（AC-13③）：走 selection 令牌；离开/取消恢复基础样式（rejected 标红语义保留）。
  // DOM 事件两态均生效（只读图同样可 hover，无选中事件源）。
  graph.on('edge:mouseenter', ({ edge }: { edge: Edge }) => {
    edge.attr('line/stroke', paletteByGraph.get(graph)?.selection ?? palette.selection);
    edge.attr('line/strokeWidth', 2);
  });
  graph.on('edge:mouseleave', ({ edge }: { edge: Edge }) => {
    restoreEdgeStroke(edge, paletteByGraph.get(graph) ?? palette);
  });
  graph.on('edge:selected', ({ edge }: { edge: Edge }) => {
    edge.attr('line/stroke', paletteByGraph.get(graph)?.selection ?? palette.selection);
    edge.attr('line/strokeWidth', 2);
  });
  graph.on('edge:unselected', ({ edge }: { edge: Edge }) => {
    restoreEdgeStroke(edge, paletteByGraph.get(graph) ?? palette);
  });
  return graph;
}

/** 主题变化 → 网格/背景刷新（节点配色由各页经 bpmnNodeDataOf 重灌；边 hover 取色同步刷新）。 */
export function applyCanvasTheme(graph: Graph, palette: CanvasPalette): void {
  paletteByGraph.set(graph, palette);
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
  /** 四向连线锚点 ports 装配（delta-r1 修复1）：缺省开启（交互态）；readOnly 渲染传 false。 */
  ports?: boolean;
}

/**
 * 模型 → 图同步（节点增删/数据刷新/连线增删/边标记刷新）：
 * 交互态与只读态共用；positions 缺失的节点以 autoLayout(model) 兜底（AC-4：不再叠于 {0,0}）。
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
  // AC-4：位置缺失节点 autoLayout 兜底（惰性求值；无缺失不计算）
  let fallbackLayout: Map<string, { x: number; y: number }> | null = null;
  for (const node of model.nodes) {
    const size = DEFAULT_NODE_SIZES[node.type];
    const position = positions.get(node.id)
      ?? (fallbackLayout ??= autoLayout(model)).get(node.id)
      ?? { x: 0, y: 0 };
    const data = bpmnNodeDataOf(node, palette, dark, labels.nameOf(node), labels.summaryOf(node), {
      running: extras.activeNodeIds?.has(node.id) === true,
    });
    if (!graph.hasCell(node.id)) {
      const added = graph.addNode({
        id: node.id,
        shape: BPMN_NODE_SHAPE,
        x: position.x,
        y: position.y,
        width: size.width,
        height: size.height,
        data,
        // delta-r1 修复1：交互态节点携带四向连线锚点 ports（magnet=true）；readOnly 渲染不带 ports
        ...(extras.ports === false ? {} : { ports: bpmnPortsOf(node.type) }),
      });
      // 位置单源重申（addNode 已带 x/y，此处幂等重申；验收桩经 position() 观测坐标面）
      added.position(position.x, position.y);
    } else {
      const cell = graph.getCellById(node.id);
      if (cell && cell.isNode()) {
        cell.setData(data, { overwrite: true });
        cell.position(position.x, position.y);
      }
    }
  }
  const modelEdgeIds = new Set(model.edges.map((edge) => edge.id));
  for (const edge of graph.getEdges()) {
    if (!modelEdgeIds.has(edge.id)) {
      graph.removeCell(edge);
    }
  }
  for (const edge of model.edges) {
    const rejected = extras.rejectEdgeIds?.has(edge.id) === true;
    const attrs = bpmnEdgeAttrs(palette, rejected);
    const edgeLabels = bpmnEdgeLabels(edge, palette);
    const cell = graph.getCellById(edge.id);
    if (!cell) {
      graph.addEdge({
        id: edge.id,
        source: { cell: edge.sourceId },
        target: { cell: edge.targetId },
        attrs,
        labels: edgeLabels,
        // delta-r2 修复1：中和 X6 静态 Edge.defaultLabel.attrs 的合并污染——
        // EdgeView.updateLabels 每次把 defaultLabel.attrs 深合入 label attrs，默认 rect 键
        // （ref:'label'）经 CSS 兜底匹配到自定义 <rect> 后 ref 解析失败抛错，attrs 应用与
        // updateLabelPositions 被中断（连线标记裸 DOM、无 transform 的实证根因）。
        defaultLabel: { attrs: {} },
        data: { rejected },
      });
    } else if (cell.isEdge()) {
      // 既有边随模型刷新（属性面板编辑 name/condition/isDefault、主题切换、驳回标红同步）
      cell.setData({ rejected }, { overwrite: true });
      cell.attr(attrs);
      cell.setLabels(edgeLabels);
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
 * 缺省 autoLayout；布局条目缺失由 syncModelToGraph autoLayout 兜底）→ 同步节点/边
 * （activeNodes running 高亮 + REJECT_EDGE 标红 + 边标记）→ 居中。
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
    // delta-r1 修复1验收③：readOnly 渲染不带 ports（只读图无连线创建入口）
    ports: false,
  });
  const cells = graph.getCells();
  if (cells.length > 0) {
    graph.centerCell(cells[0] as Cell);
  }
}
