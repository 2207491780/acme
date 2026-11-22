/**
 * BPMN 2.0 XML ↔ 前端模型互转 + 静态校验规则集（REQ-20261122-023441 确认②）。
 *
 * 纯 TS 实现（无 X6/DOM 依赖，node 测试环境可跑）：
 * - XML 解析：手写最小 XML 子集解析器（声明/注释/CDATA/实体/自闭合/属性），
 *   仅覆盖本域生成的 BPMN 2.0 交换 XML；白名单外流程元素直接拒绝（XML_INVALID）。
 * - 命名空间：标准 ns = http://www.omg.org/spec/BPMN/20100524/MODEL（前缀 bpmn:），
 *   扩展 ns = http://acme.dev/bpmn/ext/v1（前缀 acme:，REQ-20261122-023441 §2）。
 * - acme: 清单（一期）：UserTask assignee/candidateRoles/candidateDepts/candidateUsers/
 *   assigneeStrategy（一期 ALL）；ServiceTask serviceRef/callbackPath/retryPolicy；
 *   节点级 executionListeners(before/after)。
 * - 一期元素白名单：StartEvent/EndEvent/XOR/AND/UserTask/ServiceTask/
 *   EmbeddedSubProcess/BoundaryEvent(timer,interrupting)。
 * - 静态校验规则集对齐后端设计 §5.4（以后端为准）：start/end 恰一、XOR 流出条件或 default、
 *   AND fork-join 配对、acme 必填、连线端点存在/方向约束、可达性。
 */

/** 一期元素白名单（契约 §2；白名单外=二期）。 */
export type BpmnElementType =
  | 'StartEvent'
  | 'EndEvent'
  | 'XOR'
  | 'AND'
  | 'UserTask'
  | 'ServiceTask'
  | 'EmbeddedSubProcess'
  | 'BoundaryEvent';

/** 节点级监听器（acme:executionListeners；before/after 两相位，ref=委托标识）。 */
export interface ExecutionListener {
  event: 'before' | 'after';
  ref: string;
}

/** 前端节点模型（acme: 扩展属性按元素类型收敛为可选域）。 */
export interface BpmnNodeModel {
  id: string;
  type: BpmnElementType;
  name: string;
  /** UserTask：指派人（含 ${initiator}/SpEL）。 */
  assignee?: string;
  /** UserTask：候选角色/部门/用户（逗号分隔串，模型内为数组）。 */
  candidateRoles?: string[];
  candidateDepts?: string[];
  candidateUsers?: string[];
  /** UserTask：会签策略（一期 ALL）。 */
  assigneeStrategy?: 'ALL';
  /** ServiceTask：服务引用 / 回调路径 / 重试策略。 */
  serviceRef?: string;
  callbackPath?: string;
  retryPolicy?: string;
  /** BoundaryEvent：是否中断（BPMN cancelActivity 语义；缺省 true）。 */
  interrupting?: boolean;
  /** BoundaryEvent：依附的目标节点 id（UserTask/ServiceTask/EmbeddedSubProcess）。 */
  attachedToRef?: string | null;
  /** 节点级监听器（before/after）。 */
  executionListeners: ExecutionListener[];
}

/** 前端连线模型（XOR 流出条件=SpEL 串；default 走 BPMN 网关 default 属性）。 */
export interface BpmnEdgeModel {
  id: string;
  sourceId: string;
  targetId: string;
  name?: string;
  /** XOR 流出条件表达式（bpmn:conditionExpression 文本；仅 XOR 流出语义校验）。 */
  condition?: string;
  /** 该边是否为其源网关/任务的 default 流（序列化为 source 的 default 属性）。 */
  isDefault?: boolean;
}

/** 前端模型（画布状态机与 XML 互转的共同形态）。 */
export interface BpmnModel {
  nodes: BpmnNodeModel[];
  edges: BpmnEdgeModel[];
}

/** 静态校验问题（nodeId 可空=模型级问题；与后端 VALIDATION_FAILED 明细同形）。 */
export interface WorkflowValidationError {
  nodeId: string | null;
  message: string;
}

/** XML 解析/白名单拒绝错误（错误文案为 i18n 键 + 上下文，渲染端负责转译）。 */
export class BpmnXmlError extends Error {
  constructor(
    /** i18n 键（workflow.xmlError.*）。 */
    public readonly messageKey: string,
    /** 相关元素本地名（可空）。 */
    public readonly element: string | null,
    message: string,
  ) {
    super(message);
    this.name = 'BpmnXmlError';
  }
}

export const BPMN_MODEL_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
export const ACME_EXT_NS = 'http://acme.dev/bpmn/ext/v1';

/** 一期流程元素本地名白名单（含连线）。 */
const FLOW_ELEMENT_LOCALS: ReadonlySet<string> = new Set([
  'startEvent',
  'endEvent',
  'exclusiveGateway',
  'parallelGateway',
  'userTask',
  'serviceTask',
  'subProcess',
  'boundaryEvent',
  'sequenceFlow',
]);

const LOCAL_TO_TYPE: Record<string, BpmnElementType> = {
  startEvent: 'StartEvent',
  endEvent: 'EndEvent',
  exclusiveGateway: 'XOR',
  parallelGateway: 'AND',
  userTask: 'UserTask',
  serviceTask: 'ServiceTask',
  subProcess: 'EmbeddedSubProcess',
  boundaryEvent: 'BoundaryEvent',
};

const TYPE_TO_LOCAL: Record<BpmnElementType, string> = {
  StartEvent: 'startEvent',
  EndEvent: 'endEvent',
  XOR: 'exclusiveGateway',
  AND: 'parallelGateway',
  UserTask: 'userTask',
  ServiceTask: 'serviceTask',
  EmbeddedSubProcess: 'subProcess',
  BoundaryEvent: 'boundaryEvent',
};

// ———————————————————————————————— XML 基础设施 ————————————————————————————————

function escapeXmlText(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeXmlAttribute(value: string): string {
  return escapeXmlText(value).replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

function decodeEntities(text: string): string {
  return text
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex: string) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec: string) => String.fromCodePoint(parseInt(dec, 10)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

/** 最小 XML 元素树（本地名剥离前缀；attrs 保留原始键，取值经 attrLocal 按本地名查找）。 */
interface XmlElement {
  /** 本地名（prefix:x → x）。 */
  name: string;
  /** 属性原始键（可带前缀）→ 解码后值。 */
  attrs: Record<string, string>;
  children: XmlElement[];
  text: string;
}

/** 按本地名取属性（容忍任意前缀：acme:assignee 与 assignee 等价）。 */
function attrLocal(el: XmlElement, local: string): string | undefined {
  for (const [key, value] of Object.entries(el.attrs)) {
    if (key === local || key.endsWith(`:${local}`)) {
      return value;
    }
  }
  return undefined;
}

function childByLocal(el: XmlElement, local: string): XmlElement | undefined {
  return el.children.find((child) => child.name === local);
}

/** 手写最小 XML 解析器：声明/注释/DOCTYPE 跳过，CDATA 并入文本，实体解码。 */
function parseXmlDocument(xml: string): XmlElement {
  let i = 0;
  const n = xml.length;

  const fail = (key: string, element: string | null = null): never => {
    throw new BpmnXmlError(key, element, `Invalid BPMN XML at offset ${i}`);
  };

  const skip = (pattern: RegExp): void => {
    const match = pattern.exec(xml.slice(i));
    if (match) {
      i += match[0].length;
    }
  };

  const isWs = (ch: string): boolean => ch === ' ' || ch === '\t' || ch === '\r' || ch === '\n';

  const parseComment = (): void => {
    const end = xml.indexOf('-->', i);
    if (end < 0) fail('xmlError.malformed');
    i = end + 3;
  };

  const parseName = (): string => {
    const start = i;
    while (i < n && !isWs(xml[i]) && xml[i] !== '>' && xml[i] !== '/' && xml[i] !== '=') i += 1;
    if (i === start) fail('xmlError.malformed');
    return xml.slice(start, i);
  };

  const localName = (qname: string): string => {
    const idx = qname.indexOf(':');
    return idx >= 0 ? qname.slice(idx + 1) : qname;
  };

  const parseAttributes = (): Record<string, string> => {
    const attrs: Record<string, string> = {};
    for (;;) {
      while (i < n && isWs(xml[i])) i += 1;
      if (i >= n) fail('xmlError.malformed');
      const ch = xml[i];
      if (ch === '>' || ch === '/' || ch === '?') {
        return attrs;
      }
      const key = parseName();
      while (i < n && isWs(xml[i])) i += 1;
      if (xml[i] !== '=') fail('xmlError.malformed');
      i += 1;
      while (i < n && isWs(xml[i])) i += 1;
      const quote = xml[i];
      if (quote !== '"' && quote !== "'") fail('xmlError.malformed');
      i += 1;
      const valueStart = i;
      while (i < n && xml[i] !== quote) i += 1;
      if (i >= n) fail('xmlError.malformed');
      attrs[key] = decodeEntities(xml.slice(valueStart, i));
      i += 1;
    }
  };

  const parseElement = (): XmlElement => {
    // 跳过 <?...?>、<!--、<!DOCTYPE>
    for (;;) {
      skip(/\s+/y);
      if (xml.startsWith('<?', i)) {
        const end = xml.indexOf('?>', i);
        if (end < 0) fail('xmlError.malformed');
        i = end + 2;
        continue;
      }
      if (xml.startsWith('<!--', i)) {
        parseComment();
        continue;
      }
      if (xml.startsWith('<!', i)) {
        const end = xml.indexOf('>', i);
        if (end < 0) fail('xmlError.malformed');
        i = end + 1;
        continue;
      }
      break;
    }
    if (xml[i] !== '<') fail('xmlError.malformed');
    i += 1;
    const qname = parseName();
    const attrs = parseAttributes();
    const el: XmlElement = { name: localName(qname), attrs, children: [], text: '' };
    if (xml[i] === '/') {
      i += 1;
      if (xml[i] !== '>') fail('xmlError.malformed');
      i += 1;
      return el;
    }
    if (xml[i] !== '>') fail('xmlError.malformed');
    i += 1;
    // 子内容
    for (;;) {
      if (xml.startsWith('<![CDATA[', i)) {
        const end = xml.indexOf(']]>', i);
        if (end < 0) fail('xmlError.malformed');
        el.text += xml.slice(i + 9, end);
        i = end + 3;
        continue;
      }
      if (xml.startsWith('<!--', i)) {
        parseComment();
        continue;
      }
      if (xml.startsWith('</', i)) {
        i += 2;
        const close = parseName();
        if (localName(close) !== el.name) fail('xmlError.mismatchedTag', el.name);
        while (i < n && isWs(xml[i])) i += 1;
        if (xml[i] !== '>') fail('xmlError.malformed');
        i += 1;
        return el;
      }
      if (xml[i] === '<') {
        el.children.push(parseElement());
        continue;
      }
      const textStart = i;
      while (i < n && xml[i] !== '<') i += 1;
      if (i > textStart) {
        el.text += decodeEntities(xml.slice(textStart, i));
      }
      if (i >= n) fail('xmlError.malformed');
    }
  };

  const root = parseElement();
  skip(/\s+$/y);
  if (i !== n) fail('xmlError.trailingContent');
  return root;
}

// ———————————————————————————————— 序列化 ————————————————————————————————

/** 模型 → 标准 BPMN 2.0 XML（含 acme: 扩展 ns 声明；元素顺序=nodes→edges，确定性输出）。 */
export function serializeBpmnModel(model: BpmnModel, processId = 'Process_1'): string {
  const lines: string[] = [];
  lines.push('<?xml version="1.0" encoding="UTF-8"?>');
  lines.push(
    `<bpmn:definitions xmlns:bpmn="${BPMN_MODEL_NS}" xmlns:acme="${ACME_EXT_NS}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_1" targetNamespace="http://acme.dev/bpmn">`,
  );
  lines.push(`  <bpmn:process id="${escapeXmlAttribute(processId)}" isExecutable="true">`);

  for (const node of model.nodes) {
    const local = TYPE_TO_LOCAL[node.type];
    const attrs: string[] = [`id="${escapeXmlAttribute(node.id)}"`];
    if (node.name) attrs.push(`name="${escapeXmlAttribute(node.name)}"`);
    // BPMN 语义：default 流以「源节点的 default 属性指向流出连线 id」表达（非连线自身属性）
    const defaultEdge = model.edges.find(
      (edge) => edge.isDefault && edge.sourceId === node.id
        && (node.type === 'XOR' || node.type === 'AND' || node.type === 'UserTask' || node.type === 'ServiceTask'),
    );
    if (defaultEdge) attrs.push(`default="${escapeXmlAttribute(defaultEdge.id)}"`);
    if (node.type === 'UserTask') {
      if (node.assignee) attrs.push(`acme:assignee="${escapeXmlAttribute(node.assignee)}"`);
      if (node.candidateRoles?.length) attrs.push(`acme:candidateRoles="${escapeXmlAttribute(node.candidateRoles.join(','))}"`);
      if (node.candidateDepts?.length) attrs.push(`acme:candidateDepts="${escapeXmlAttribute(node.candidateDepts.join(','))}"`);
      if (node.candidateUsers?.length) attrs.push(`acme:candidateUsers="${escapeXmlAttribute(node.candidateUsers.join(','))}"`);
      attrs.push(`acme:assigneeStrategy="${escapeXmlAttribute(node.assigneeStrategy ?? 'ALL')}"`);
    }
    if (node.type === 'ServiceTask') {
      if (node.serviceRef) attrs.push(`acme:serviceRef="${escapeXmlAttribute(node.serviceRef)}"`);
      if (node.callbackPath) attrs.push(`acme:callbackPath="${escapeXmlAttribute(node.callbackPath)}"`);
      if (node.retryPolicy) attrs.push(`acme:retryPolicy="${escapeXmlAttribute(node.retryPolicy)}"`);
    }
    if (node.type === 'BoundaryEvent') {
      attrs.push(`attachedToRef="${escapeXmlAttribute(node.attachedToRef ?? '')}"`);
      attrs.push(`cancelActivity="${node.interrupting === false ? 'false' : 'true'}"`);
    }
    const listeners = node.executionListeners ?? [];
    if (listeners.length > 0) {
      lines.push(`    <bpmn:${local} ${attrs.join(' ')}>`);
      lines.push('      <bpmn:extensionElements>');
      lines.push('        <acme:executionListeners>');
      for (const listener of listeners) {
        lines.push(
          `          <acme:executionListener event="${listener.event}" ref="${escapeXmlAttribute(listener.ref)}"/>`,
        );
      }
      lines.push('        </acme:executionListeners>');
      lines.push('      </bpmn:extensionElements>');
      lines.push(`    </bpmn:${local}>`);
    } else if (node.type === 'EmbeddedSubProcess') {
      lines.push(`    <bpmn:${local} ${attrs.join(' ')} triggeredByEvent="false"/>`);
    } else {
      lines.push(`    <bpmn:${local} ${attrs.join(' ')}/>`);
    }
  }

  for (const edge of model.edges) {
    const attrs: string[] = [
      `id="${escapeXmlAttribute(edge.id)}"`,
      `sourceRef="${escapeXmlAttribute(edge.sourceId)}"`,
      `targetRef="${escapeXmlAttribute(edge.targetId)}"`,
    ];
    if (edge.name) attrs.push(`name="${escapeXmlAttribute(edge.name)}"`);
    if (edge.condition) {
      lines.push(`    <bpmn:sequenceFlow ${attrs.join(' ')}>`);
      lines.push('      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">');
      lines.push(`        ${escapeXmlText(edge.condition)}`);
      lines.push('      </bpmn:conditionExpression>');
      lines.push('    </bpmn:sequenceFlow>');
    } else {
      lines.push(`    <bpmn:sequenceFlow ${attrs.join(' ')}/>`);
    }
  }

  lines.push('  </bpmn:process>');
  lines.push('</bpmn:definitions>');
  return lines.join('\n');
}

// ———————————————————————————————— 解析 ————————————————————————————————

function splitCsv(value: string | undefined): string[] | undefined {
  if (value === undefined) return undefined;
  const items = value.split(',').map((item) => item.trim()).filter((item) => item !== '');
  return items.length > 0 ? items : undefined;
}

function parseListeners(el: XmlElement): ExecutionListener[] {
  const ext = childByLocal(el, 'extensionElements');
  if (!ext) return [];
  const list = childByLocal(ext, 'executionListeners');
  if (!list) return [];
  return list.children
    .filter((child) => child.name === 'executionListener')
    .map((child) => {
      const event = attrLocal(child, 'event');
      const ref = attrLocal(child, 'ref') ?? '';
      return { event: event === 'after' ? 'after' : 'before', ref } as ExecutionListener;
    })
    .filter((listener) => listener.ref !== '');
}

/** BPMN 交换 XML → 前端模型；白名单外流程元素/结构不符即抛 BpmnXmlError。 */
export function parseBpmnXml(xml: string): BpmnModel {
  if (typeof xml !== 'string' || xml.trim() === '') {
    throw new BpmnXmlError('xmlError.empty', null, 'Empty BPMN XML');
  }
  let root: XmlElement;
  try {
    root = parseXmlDocument(xml);
  } catch (ex: unknown) {
    if (ex instanceof BpmnXmlError) throw ex;
    throw new BpmnXmlError('xmlError.malformed', null, 'Malformed BPMN XML');
  }
  if (root.name !== 'definitions') {
    throw new BpmnXmlError('xmlError.notDefinitions', root.name, 'Root element must be definitions');
  }
  const process = childByLocal(root, 'process');
  if (!process) {
    throw new BpmnXmlError('xmlError.noProcess', null, 'Missing process element');
  }

  const nodes: BpmnNodeModel[] = [];
  const edges: BpmnEdgeModel[] = [];
  const defaults = new Map<string, string>(); // sourceId → default edgeId

  for (const el of process.children) {
    if (!FLOW_ELEMENT_LOCALS.has(el.name)) {
      throw new BpmnXmlError('xmlError.elementNotWhitelisted', el.name, `Element not in phase-1 whitelist: ${el.name}`);
    }
    if (el.name === 'sequenceFlow') {
      const id = attrLocal(el, 'id');
      const sourceId = attrLocal(el, 'sourceRef');
      const targetId = attrLocal(el, 'targetRef');
      if (!id || !sourceId || !targetId) {
        throw new BpmnXmlError('xmlError.flowMissingRefs', el.name, 'sequenceFlow missing id/sourceRef/targetRef');
      }
      const conditionEl = childByLocal(el, 'conditionExpression');
      edges.push({
        id,
        sourceId,
        targetId,
        name: attrLocal(el, 'name'),
        condition: conditionEl?.text.trim() || undefined,
      });
      continue;
    }
    const id = attrLocal(el, 'id');
    if (!id) {
      throw new BpmnXmlError('xmlError.nodeMissingId', el.name, 'Flow node missing id');
    }
    const defaultRef = attrLocal(el, 'default');
    if (defaultRef) defaults.set(id, defaultRef);
    const node: BpmnNodeModel = {
      id,
      type: LOCAL_TO_TYPE[el.name],
      name: attrLocal(el, 'name') ?? '',
      executionListeners: parseListeners(el),
    };
    if (node.type === 'UserTask') {
      const assignee = attrLocal(el, 'assignee');
      const roles = splitCsv(attrLocal(el, 'candidateRoles'));
      const depts = splitCsv(attrLocal(el, 'candidateDepts'));
      const users = splitCsv(attrLocal(el, 'candidateUsers'));
      if (assignee !== undefined) node.assignee = assignee;
      if (roles !== undefined) node.candidateRoles = roles;
      if (depts !== undefined) node.candidateDepts = depts;
      if (users !== undefined) node.candidateUsers = users;
      const strategy = attrLocal(el, 'assigneeStrategy');
      node.assigneeStrategy = strategy === 'ALL' ? 'ALL' : 'ALL'; // 一期 ALL
    }
    if (node.type === 'ServiceTask') {
      const serviceRef = attrLocal(el, 'serviceRef');
      const callbackPath = attrLocal(el, 'callbackPath');
      const retryPolicy = attrLocal(el, 'retryPolicy');
      if (serviceRef !== undefined) node.serviceRef = serviceRef;
      if (callbackPath !== undefined) node.callbackPath = callbackPath;
      if (retryPolicy !== undefined) node.retryPolicy = retryPolicy;
    }
    if (node.type === 'BoundaryEvent') {
      node.attachedToRef = attrLocal(el, 'attachedToRef') ?? null;
      node.interrupting = attrLocal(el, 'cancelActivity') !== 'false';
    }
    nodes.push(node);
  }

  for (const [sourceId, edgeId] of defaults) {
    const edge = edges.find((item) => item.id === edgeId && item.sourceId === sourceId);
    if (edge) edge.isDefault = true;
  }

  return { nodes, edges };
}

// ———————————————————————————————— 静态校验（后端设计 §5.4 对齐） ————————————————————————————————

/**
 * 静态校验（一期规则集；以后端校验为准，TL 裁决 2026-11-22 对齐 ProcessModelCompiler）：
 * 1. start/end 恰一；2. 连线端点存在/无自环；3. start 无入边、end 无出边；
 * 4. XOR 流出条件或 default（default 恰一）；5. AND fork-join 配对；
 * 6. acme 必填（UserTask 指派四形态其一；ServiceTask serviceRef 与 callbackPath 两者都必填）；
 * 7. BoundaryEvent 依附目标合法；8. id 唯一；9. 从 start 可达（豁免经 attachedToRef 依附的
 *    边界事件——激活来自宿主活动+定时器，不从 start 可达）。
 * 偏差记录：设计 §5「网关出边 ≥2」不强制（后端部署校验无此规则，BPMN 标准 XOR/AND 单出边合法）。
 */
export function validateBpmnModel(model: BpmnModel): WorkflowValidationError[] {
  const errors: WorkflowValidationError[] = [];
  const nodes = model.nodes;
  const edges = model.edges;
  const seenIds = new Set<string>();
  for (const node of nodes) {
    if (seenIds.has(node.id)) {
      errors.push({ nodeId: node.id, message: `节点 id 重复：${node.id}` });
    }
    seenIds.add(node.id);
  }

  const starts = nodes.filter((node) => node.type === 'StartEvent');
  const ends = nodes.filter((node) => node.type === 'EndEvent');
  if (starts.length !== 1) {
    errors.push({ nodeId: null, message: starts.length === 0 ? '流程缺少开始事件' : `开始事件必须恰一个（当前 ${starts.length} 个）` });
  }
  if (ends.length !== 1) {
    errors.push({ nodeId: null, message: ends.length === 0 ? '流程缺少结束事件' : `结束事件必须恰一个（当前 ${ends.length} 个）` });
  }

  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  for (const edge of edges) {
    if (!nodeById.has(edge.sourceId) || !nodeById.has(edge.targetId)) {
      errors.push({ nodeId: edge.sourceId, message: `连线 ${edge.id} 的端点不存在（${edge.sourceId} → ${edge.targetId}）` });
    }
    if (edge.sourceId === edge.targetId) {
      errors.push({ nodeId: edge.sourceId, message: `连线不允许自环（${edge.id}）` });
    }
  }

  const outDegree = new Map<string, number>();
  const inDegree = new Map<string, number>();
  for (const edge of edges) {
    outDegree.set(edge.sourceId, (outDegree.get(edge.sourceId) ?? 0) + 1);
    inDegree.set(edge.targetId, (inDegree.get(edge.targetId) ?? 0) + 1);
  }

  if (starts.length === 1 && (inDegree.get(starts[0].id) ?? 0) > 0) {
    errors.push({ nodeId: starts[0].id, message: '开始事件不允许入边' });
  }
  if (ends.length === 1 && (outDegree.get(ends[0].id) ?? 0) > 0) {
    errors.push({ nodeId: ends[0].id, message: '结束事件不允许出边' });
  }

  const andNodes = nodes.filter((node) => node.type === 'AND');
  const hasAndFork = andNodes.some((node) => (outDegree.get(node.id) ?? 0) >= 2);
  const hasAndJoin = andNodes.some((node) => (inDegree.get(node.id) ?? 0) >= 2);
  if (hasAndFork && !hasAndJoin) {
    errors.push({ nodeId: null, message: '并行分叉缺少配对的并行汇聚（AND fork-join）' });
  }
  if (hasAndJoin && !hasAndFork) {
    errors.push({ nodeId: null, message: '并行汇聚缺少配对的并行分叉（AND fork-join）' });
  }

  for (const node of nodes) {
    // R8（TL 裁决 2026-11-22）：「网关出边 ≥2」不强制——后端部署校验无此规则，仅校验 XOR 流出条件完备
    if (node.type === 'XOR') {
      const outEdges = edges.filter((edge) => edge.sourceId === node.id);
      const defaultCount = outEdges.filter((edge) => edge.isDefault).length;
      const conditionCount = outEdges.filter((edge) => !edge.isDefault && (edge.condition ?? '').trim() !== '').length;
      if (defaultCount > 1) {
        errors.push({ nodeId: node.id, message: `网关 ${node.name || node.id} 只能有一条 default 流` });
      }
      if (defaultCount === 0 && conditionCount !== outEdges.length) {
        errors.push({ nodeId: node.id, message: `网关 ${node.name || node.id} 的流出连线须带条件或设置 default` });
      }
    }
    if (node.type === 'UserTask') {
      const hasAssignee = (node.assignee ?? '').trim() !== '';
      const hasCandidates =
        (node.candidateRoles?.length ?? 0) > 0 ||
        (node.candidateDepts?.length ?? 0) > 0 ||
        (node.candidateUsers?.length ?? 0) > 0;
      if (!hasAssignee && !hasCandidates) {
        errors.push({ nodeId: node.id, message: `用户任务 ${node.name || node.id} 缺少指派（assignee 或候选角色/部门/用户）` });
      }
    }
    if (node.type === 'ServiceTask') {
      // R6（TL 裁决，对齐 ProcessModelCompiler.java:236-243）：serviceRef 与 callbackPath 两者都必填
      if ((node.serviceRef ?? '').trim() === '') {
        errors.push({ nodeId: node.id, message: `服务任务 ${node.name || node.id} 缺少 serviceRef` });
      }
      if ((node.callbackPath ?? '').trim() === '') {
        errors.push({ nodeId: node.id, message: `服务任务 ${node.name || node.id} 缺少 callbackPath` });
      }
    }
    if (node.type === 'BoundaryEvent') {
      const target = node.attachedToRef ? nodeById.get(node.attachedToRef) : undefined;
      if (!target || (target.type !== 'UserTask' && target.type !== 'ServiceTask' && target.type !== 'EmbeddedSubProcess')) {
        errors.push({ nodeId: node.id, message: `边界事件 ${node.name || node.id} 须依附于用户/服务任务或子流程` });
      }
      if ((inDegree.get(node.id) ?? 0) > 0) {
        errors.push({ nodeId: node.id, message: `边界事件 ${node.name || node.id} 不允许入边` });
      }
    }
  }

  // 可达性：从唯一 start 出发；start 缺失/多值时跳过（已另行报错）
  if (starts.length === 1) {
    const adjacency = new Map<string, string[]>();
    for (const edge of edges) {
      const list = adjacency.get(edge.sourceId) ?? [];
      list.push(edge.targetId);
      adjacency.set(edge.sourceId, list);
    }
    const visited = new Set<string>([starts[0].id]);
    // 可达性豁免（裁决②）：经 attachedToRef 依附宿主活动的边界事件，激活来自宿主+定时器，
    // 不从 start 可达（后端 BOUNDARY_TIMER 同样禁其入边）；未依附（attachedToRef 为空）不豁免
    for (const node of nodes) {
      if (node.type === 'BoundaryEvent' && node.attachedToRef) {
        visited.add(node.id);
      }
    }
    const queue = [starts[0].id];
    while (queue.length > 0) {
      const current = queue.shift();
      if (current === undefined) break;
      for (const next of adjacency.get(current) ?? []) {
        if (!visited.has(next)) {
          visited.add(next);
          queue.push(next);
        }
      }
    }
    for (const node of nodes) {
      if (!visited.has(node.id)) {
        errors.push({ nodeId: node.id, message: `节点 ${node.name || node.id} 从开始事件不可达` });
      }
    }
  }

  return errors;
}
