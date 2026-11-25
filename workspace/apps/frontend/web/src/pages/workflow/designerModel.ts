import {
  type BpmnEdgeModel,
  type BpmnElementType,
  type BpmnModel,
  type BpmnNodeModel,
  parseBpmnXml,
  serializeBpmnModel,
  validateBpmnModel,
  type WorkflowValidationError,
} from '@acme/core';

/**
 * 设计器模型状态机（REQ-20261122-023441 确认⑧测试口径）：
 * headless 纯函数集合——节点/连线增删改、validateConnection 白名单、布局序列化、保存载荷。
 * 不依赖 X6 与 AntD：画布交互类用例不做 jsdom 强测，模型状态机+面板联动+保存载荷为测试面。
 */

/** 一期 Palette 白名单顺序（契约 §2）。 */
export const PALETTE_ITEMS: readonly BpmnElementType[] = [
  'StartEvent',
  'EndEvent',
  'XOR',
  'AND',
  'UserTask',
  'ServiceTask',
  'EmbeddedSubProcess',
  'BoundaryEvent',
];

/**
 * 元素默认尺寸（画布渲染与自动布局共用，REQ-20261124-224523 AC-2 语义形状适配）。
 * 事件/网关 = 图形区 + 名称标签区（标签在图形下方，高约 22px，可按视觉微调）；任务/子流程保持整格卡片。
 * 最终值：StartEvent/EndEvent {40,62}、XOR/AND {50,72}、BoundaryEvent {36,58}、
 * UserTask/ServiceTask {128,48}、EmbeddedSubProcess {200,120}。
 */
export const DEFAULT_NODE_SIZES: Record<BpmnElementType, { width: number; height: number }> = {
  StartEvent: { width: 40, height: 62 },
  EndEvent: { width: 40, height: 62 },
  XOR: { width: 50, height: 72 },
  AND: { width: 50, height: 72 },
  UserTask: { width: 128, height: 48 },
  ServiceTask: { width: 128, height: 48 },
  EmbeddedSubProcess: { width: 200, height: 120 },
  BoundaryEvent: { width: 36, height: 58 },
};

/**
 * 语义形状图形区高度（单源：NodeShape 渲染标签区=cell 高−图形区高；
 * canvasFactory 自定义 anchor 以图形几何中心=（宽/2，图形区高/2）作为连线锚点，
 * 补偿标签区导致的锚点下移，保证连线起止于图形边缘而非标签区）。
 */
export const NODE_SHAPE_HEIGHTS: Record<BpmnElementType, number> = {
  StartEvent: 40,
  EndEvent: 40,
  XOR: 50,
  AND: 50,
  UserTask: 48,
  ServiceTask: 48,
  EmbeddedSubProcess: 120,
  BoundaryEvent: 36,
};

let nodeSeq = 0;
/** 新节点 id（会话内单调；导入场景以 XML 内 id 为准）。 */
export function nextNodeId(type: BpmnElementType): string {
  nodeSeq += 1;
  return `${type.toLowerCase()}-${nodeSeq}`;
}

let edgeSeq = 0;
export function nextEdgeId(): string {
  edgeSeq += 1;
  return `flow-${edgeSeq}`;
}

/** 新节点默认属性（面板按元素类型渲染的初值；UserTask assigneeStrategy 一期固定 ALL）。 */
export function createNodeModel(type: BpmnElementType, id?: string): BpmnNodeModel {
  const node: BpmnNodeModel = {
    id: id ?? nextNodeId(type),
    type,
    name: '',
    executionListeners: [],
  };
  if (type === 'UserTask') {
    node.assigneeStrategy = 'ALL';
  }
  if (type === 'BoundaryEvent') {
    node.interrupting = true;
    node.attachedToRef = null;
  }
  return node;
}

/** 空模型（AC-1：新建定义跳转设计器，StartEvent 预置）。 */
export function emptyModel(): BpmnModel {
  return { nodes: [createNodeModel('StartEvent', 'start-1')], edges: [] };
}

/** 节点摘要行（PropertyPanel/DesignerPage 与实例详情只读渲染共用口径）。 */
export function nodeSummary(node: BpmnModel['nodes'][number]): string | undefined {
  if (node.type === 'UserTask') {
    const candidates = [...(node.candidateRoles ?? []), ...(node.candidateDepts ?? []), ...(node.candidateUsers ?? [])];
    return node.assignee ?? (candidates.length > 0 ? candidates.join(',') : undefined);
  }
  if (node.type === 'ServiceTask') {
    return node.serviceRef ?? node.callbackPath;
  }
  if (node.type === 'BoundaryEvent') {
    return node.attachedToRef ?? undefined;
  }
  return undefined;
}

// ———————————————————————————————— 连线白名单（AC-4） ————————————————————————————————

/** 面板选中态（null=无选中；node/edge 二选一；PropertyPanel 与 DesignerPage 共用）。 */
export type Selection =
  | { kind: 'node'; id: string }
  | { kind: 'edge'; id: string }
  | null;

export type ConnectRejectReason =
  | 'selfLoop'
  | 'unknownEndpoint'
  | 'startIncoming'
  | 'endOutgoing'
  | 'targetBoundary'
  | 'sourceBoundary'
  | 'duplicate';

/** 连线方向白名单（validateConnection 对齐契约 AC-4：目标类型/无自环/start 无入边/end 无出边）。 */
const OUTGOING_TARGETS: Record<BpmnElementType, readonly BpmnElementType[]> = {
  StartEvent: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
  EndEvent: [],
  XOR: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
  AND: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
  UserTask: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
  ServiceTask: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
  EmbeddedSubProcess: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
  // 边界事件：依附节点的异常/超时出口，只出不进
  BoundaryEvent: ['EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess'],
};

/** 连线可行性判定（纯函数；X6 validateConnection 与程序化 connect 共用）。 */
export function canConnect(
  model: BpmnModel,
  sourceId: string,
  targetId: string,
): { ok: boolean; reason?: ConnectRejectReason } {
  if (sourceId === targetId) return { ok: false, reason: 'selfLoop' };
  const source = model.nodes.find((node) => node.id === sourceId);
  const target = model.nodes.find((node) => node.id === targetId);
  if (!source || !target) return { ok: false, reason: 'unknownEndpoint' };
  if (target.type === 'StartEvent') return { ok: false, reason: 'startIncoming' };
  if (target.type === 'BoundaryEvent') return { ok: false, reason: 'targetBoundary' };
  if (source.type === 'EndEvent') return { ok: false, reason: 'endOutgoing' };
  if (!OUTGOING_TARGETS[source.type].includes(target.type)) return { ok: false, reason: 'targetBoundary' };
  if (model.edges.some((edge) => edge.sourceId === sourceId && edge.targetId === targetId)) {
    return { ok: false, reason: 'duplicate' };
  }
  return { ok: true };
}

/** 追加连线（白名单拒绝返回原模型；reason 供消息提示）。 */
export function connect(
  model: BpmnModel,
  sourceId: string,
  targetId: string,
  edgeId = nextEdgeId(),
): { model: BpmnModel; edge: BpmnEdgeModel | null; reason?: ConnectRejectReason } {
  const verdict = canConnect(model, sourceId, targetId);
  if (!verdict.ok) return { model, edge: null, reason: verdict.reason };
  const edge: BpmnEdgeModel = { id: edgeId, sourceId, targetId };
  return { model: { ...model, edges: [...model.edges, edge] }, edge };
}

// ———————————————————————————————— 节点/连线编辑 ————————————————————————————————

export function updateNode(model: BpmnModel, id: string, patch: Partial<BpmnNodeModel>): BpmnModel {
  return { ...model, nodes: model.nodes.map((node) => (node.id === id ? { ...node, ...patch } : node)) };
}

export function removeNode(model: BpmnModel, id: string): BpmnModel {
  // 边界事件依附引用随目标删除清空
  return {
    nodes: model.nodes
      .filter((node) => node.id !== id)
      .map((node) => (node.attachedToRef === id ? { ...node, attachedToRef: null } : node)),
    edges: model.edges.filter((edge) => edge.sourceId !== id && edge.targetId !== id),
  };
}

export function updateEdge(model: BpmnModel, id: string, patch: Partial<BpmnEdgeModel>): BpmnModel {
  return { ...model, edges: model.edges.map((edge) => (edge.id === id ? { ...edge, ...patch } : edge)) };
}

export function removeEdge(model: BpmnModel, id: string): BpmnModel {
  return { ...model, edges: model.edges.filter((edge) => edge.id !== id) };
}

/** 重新校验（静态规则集；面板逐条渲染面）。 */
export function runValidation(model: BpmnModel): WorkflowValidationError[] {
  return validateBpmnModel(model);
}

// ———————————————————————————————— 导入 / 布局 / 保存载荷 ————————————————————————————————

/** XML 导入（解析失败抛 BpmnXmlError，错误反馈不入画布——AC-7）。 */
export function importModel(xml: string): BpmnModel {
  return parseBpmnXml(xml);
}

export interface CanvasLayout {
  nodes: Array<{ id: string; x: number; y: number }>;
}

/** 布局 JSON 解析（外部输入运行时校验；损坏/形状不符返回 null，AC-7 布局缺省自动布局）。 */
export function parseLayoutJson(layoutJson: string | null): CanvasLayout | null {
  if (!layoutJson || layoutJson.trim() === '') return null;
  try {
    const parsed: unknown = JSON.parse(layoutJson);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const candidate = parsed as { nodes?: unknown };
    if (!Array.isArray(candidate.nodes)) return null;
    const nodes: CanvasLayout['nodes'] = [];
    for (const raw of candidate.nodes) {
      if (typeof raw !== 'object' || raw === null) return null;
      const item = raw as Record<string, unknown>;
      if (typeof item.id !== 'string' || item.id === '') return null;
      if (typeof item.x !== 'number' || !Number.isFinite(item.x)) return null;
      if (typeof item.y !== 'number' || !Number.isFinite(item.y)) return null;
      nodes.push({ id: item.id, x: item.x, y: item.y });
    }
    return { nodes };
  } catch {
    return null;
  }
}

/** 画布位置 → 布局 JSON（与 bpmnXml 单请求保存，契约确认⑤）。 */
export function serializeLayout(positions: Iterable<{ id: string; x: number; y: number }>): string {
  return JSON.stringify({ nodes: [...positions] });
}

/**
 * 自动布局（AC-7：导入布局缺省时；5 列网格从左到右按层排布——
 * 一期按模型声明序 + 简单列推进，不做拓扑分层求解）。
 * REQ-20261124-224523 AC-4：列距/行距 ≥ 子流程尺寸（200×120）+ 安全间距，混排不重叠。
 */
export function autoLayout(model: BpmnModel, originX = 60, originY = 80): Map<string, { x: number; y: number }> {
  const positions = new Map<string, { x: number; y: number }>();
  const perColumn = 4;
  const colGap = 240;
  const rowGap = 150;
  model.nodes.forEach((node, index) => {
    const column = Math.floor(index / perColumn);
    const row = index % perColumn;
    positions.set(node.id, { x: originX + column * colGap, y: originY + row * rowGap });
  });
  return positions;
}

/** 保存载荷（AC-6：XML+layoutJson 单请求；XML 经 bpmn-io 序列化含 acme: 扩展 ns）。 */
export function buildSavePayload(
  model: BpmnModel,
  positions: Map<string, { x: number; y: number }>,
  processId = 'Process_1',
): { bpmnXml: string; layoutJson: string } {
  return {
    bpmnXml: serializeBpmnModel(model, processId),
    layoutJson: serializeLayout(
      [...positions.entries()]
        .filter(([id]) => model.nodes.some((node) => node.id === id))
        .map(([id, point]) => ({ id, x: point.x, y: point.y })),
    ),
  };
}

// ———————————————————————————————— 版本对比（AC-11：双栏文本 diff 自实现） ————————————————————————————————

export type DiffOp = 'same' | 'add' | 'del';

export interface DiffRow {
  op: DiffOp;
  text: string;
}

/** 经典 LCS 逐行 diff（行数 ≤ ~1e3 场景；O(n·m) DP 一期足够）。 */
export function diffLines(left: string, right: string): DiffRow[] {
  const a = left === '' ? [] : left.split('\n');
  const b = right === '' ? [] : right.split('\n');
  const n = a.length;
  const m = b.length;
  // dp[i][j] = a[i..] 与 b[j..] 的 LCS 长度
  const dp: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i -= 1) {
    for (let j = m - 1; j >= 0; j -= 1) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const rows: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      rows.push({ op: 'same', text: a[i] });
      i += 1;
      j += 1;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      rows.push({ op: 'del', text: a[i] });
      i += 1;
    } else {
      rows.push({ op: 'add', text: b[j] });
      j += 1;
    }
  }
  while (i < n) {
    rows.push({ op: 'del', text: a[i] });
    i += 1;
  }
  while (j < m) {
    rows.push({ op: 'add', text: b[j] });
    j += 1;
  }
  return rows;
}

/** diff 变更行计数（面板摘要）。 */
export function diffChangeCount(rows: DiffRow[]): number {
  return rows.filter((row) => row.op !== 'same').length;
}
