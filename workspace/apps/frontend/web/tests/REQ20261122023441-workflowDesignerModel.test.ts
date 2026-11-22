/**
 * REQ-20261122-023441 BPM-2——设计器模型状态机契约用例（AC-3/AC-4/AC-6/AC-7/AC-11 模型面）。
 *
 * 契约口径（contracts/REQ-20261122-023441.md AC-3/4/6/7/11 + 确认⑧）：
 *   - AC-3：Palette 一期白名单 8 元素（白名单外元素不出现——模型面=PALETTE_ITEMS 精确集合）；
 *     X6 画布拖拽交互 jsdom 受限，画布视觉/拖拽留浏览器实测（报告注明）。
 *   - AC-4：连线白名单校验（目标类型/无自环/start 无入边/end 无出边/重复连线/未知端点/
 *     边界事件目标与源不可连线）——canConnect 每规则一正一反；gateway 出边≥2 由
 *     core validateBpmnModel 承载（bpmnIo 测试）。
 *   - AC-6：保存载荷 buildSavePayload = { bpmnXml（经 bpmn-io 序列化含 acme: 扩展 ns）, layoutJson }；
 *     已部署定义保存被拒（仅草稿态）由 msw 用例 01-0005 承载。
 *   - AC-7：导入（parseBpmnXml 经 importModel）+ 布局缺省自动布局 autoLayout +
 *     布局 JSON 运行时校验 parseLayoutJson（损坏/形状不符 → null 不入画布）。
 *   - AC-11：版本对比=双栏文本 diff 自实现（diffLines/diffChangeCount）。
 *
 * 装配：纯 TS 模型面（node 环境零 DOM 依赖），不渲染 X6 画布。
 */
import { describe, expect, it } from 'vitest';
import {
  autoLayout,
  buildSavePayload,
  canConnect,
  connect,
  createNodeModel,
  diffChangeCount,
  diffLines,
  emptyModel,
  importModel,
  PALETTE_ITEMS,
  parseLayoutJson,
  serializeLayout,
  updateNode,
} from '../src/pages/workflow/designerModel';
import {
  ACME_EXT_NS,
  BPMN_MODEL_NS,
  type BpmnModel,
  type BpmnNodeModel,
} from '@acme/core';

const node = (overrides: Partial<BpmnNodeModel> & { id: string; type: BpmnNodeModel['type'] }): BpmnNodeModel => ({
  name: '',
  executionListeners: [],
  ...overrides,
});

/** 最小合法模型：start → end。 */
const minimalModel = (): BpmnModel => ({
  nodes: [
    node({ id: 'start', type: 'StartEvent' }),
    node({ id: 'end', type: 'EndEvent' }),
  ],
  edges: [{ id: 'f0', sourceId: 'start', targetId: 'end' }],
});

// ————— AC-3：Palette 一期白名单（模型面） —————

describe('REQ20261122023441 设计器 Palette 白名单（AC-3 模型面）', () => {
  it('should_expose_phase1_whitelist_elements_only_when_palette_rendered', () => {
    // 白名单 8 元素精确集合（白名单外 CallActivity/Inclusive/多实例等不出现）
    expect([...PALETTE_ITEMS].sort()).toEqual([
      'AND', 'BoundaryEvent', 'EmbeddedSubProcess', 'EndEvent',
      'ServiceTask', 'StartEvent', 'UserTask', 'XOR',
    ]);
  });

  it('should_create_node_model_with_defaults_when_element_placed', () => {
    // 拖入元素的模型态默认属性（画布视觉留浏览器实测）
    const userTask = createNodeModel('UserTask');
    expect(userTask.type).toBe('UserTask');
    expect(userTask.executionListeners).toEqual([]);
    const start = createNodeModel('StartEvent');
    expect(start.type).toBe('StartEvent');
    // nextNodeId 唯一递增（模型树同步前提）
    expect(createNodeModel('UserTask').id).not.toBe(userTask.id);
    // AC-1：新建草稿空画布预置 StartEvent（无连线）
    const empty = emptyModel();
    expect(empty.edges).toEqual([]);
    expect(empty.nodes.length).toBe(1);
    expect(empty.nodes[0]?.type).toBe('StartEvent');
  });
});

// ————— AC-4：连线白名单（canConnect/connect 每规则一正一反） —————

describe('REQ20261122023441 设计器连线白名单（AC-4）', () => {
  const connected = (): BpmnModel => ({
    nodes: [
      node({ id: 'start', type: 'StartEvent' }),
      node({ id: 'task-1', type: 'UserTask', assignee: '1001' }),
      node({ id: 'svc-1', type: 'ServiceTask', serviceRef: 's', callbackPath: '/cb' }),
      node({ id: 'xor', type: 'XOR' }),
      node({ id: 'and', type: 'AND' }),
      node({ id: 'sub-1', type: 'EmbeddedSubProcess' }),
      node({ id: 'boundary-1', type: 'BoundaryEvent', attachedToRef: 'task-1' }),
      node({ id: 'end', type: 'EndEvent' }),
    ],
    edges: [{ id: 'f0', sourceId: 'start', targetId: 'task-1' }],
  });

  it('should_allow_connection_when_target_in_direction_whitelist', () => {
    // 正例矩阵：白名单方向逐一放行（含边界事件流出）
    const model = connected();
    expect(canConnect(model, 'start', 'end').ok).toBe(true);
    expect(canConnect(model, 'start', 'xor').ok).toBe(true);
    expect(canConnect(model, 'start', 'and').ok).toBe(true);
    expect(canConnect(model, 'task-1', 'end').ok).toBe(true);
    expect(canConnect(model, 'svc-1', 'end').ok).toBe(true);
    expect(canConnect(model, 'xor', 'sub-1').ok).toBe(true);
    expect(canConnect(model, 'boundary-1', 'end').ok).toBe(true);
    // connect 正例：模型新增边（用无连线的裸节点模型，避免撞 minimalModel 已有 start→end 的 duplicate 规则）
    const fresh: BpmnModel = { nodes: minimalModel().nodes, edges: [] };
    const { model: next, edge } = connect(fresh, 'start', 'end', 'f-new');
    expect(edge?.sourceId).toBe('start');
    expect(next.edges.some((e) => e.id === 'f-new')).toBe(true);
  });

  it('should_reject_self_loop_when_source_equals_target', () => {
    const verdict = canConnect(connected(), 'task-1', 'task-1');
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toBe('selfLoop');
    // connect 拒绝后模型不变
    const before = connected();
    const { model, edge } = connect(before, 'task-1', 'task-1');
    expect(edge).toBeNull();
    expect(model).toEqual(before);
  });

  it('should_reject_incoming_edge_when_target_is_start_event', () => {
    const verdict = canConnect(connected(), 'task-1', 'start');
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toBe('startIncoming');
  });

  it('should_reject_outgoing_edge_when_source_is_end_event', () => {
    const verdict = canConnect(connected(), 'end', 'task-1');
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toBe('endOutgoing');
  });

  it('should_reject_boundary_event_as_connection_target', () => {
    const verdict = canConnect(connected(), 'task-1', 'boundary-1');
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toBe('targetBoundary');
  });

  it('should_reject_connection_when_endpoint_unknown', () => {
    const verdict = canConnect(connected(), 'ghost', 'end');
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toBe('unknownEndpoint');
    expect(canConnect(connected(), 'start', 'ghost').ok).toBe(false);
  });

  it('should_reject_duplicate_edge_when_pair_already_connected', () => {
    const model = connected(); // 已有 start→task-1
    const verdict = canConnect(model, 'start', 'task-1');
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toBe('duplicate');
  });
});

// ————— AC-6：保存载荷（bpmnXml+layoutJson 单载荷） —————

describe('REQ20261122023441 设计器保存载荷（AC-6 载荷面）', () => {
  it('should_build_save_payload_with_bpmn_xml_and_layout_json_when_draft_saved', () => {
    const model = minimalModel();
    model.nodes.push(node({ id: 'task-1', type: 'UserTask', assignee: '${initiator}' }));
    model.edges.push({ id: 'f1', sourceId: 'end', targetId: 'task-1' });
    const positions = new Map([
      ['start', { x: 60, y: 80 }],
      ['task-1', { x: 250, y: 80 }],
      ['end', { x: 440, y: 80 }],
    ]);
    const payload = buildSavePayload(model, positions);
    // bpmnXml：标准 BPMN ns + acme: 扩展 ns（AC-6：XML 经 bpmn-io 序列化含 acme: 扩展 ns）
    expect(payload.bpmnXml).toContain(BPMN_MODEL_NS);
    expect(payload.bpmnXml).toContain(ACME_EXT_NS);
    expect(payload.bpmnXml).toContain('task-1');
    // layoutJson：可解析且含节点坐标（PUT body 单请求两字段齐备）
    const layout = parseLayoutJson(payload.layoutJson);
    expect(layout, 'layoutJson 应可解析').not.toBeNull();
    expect(layout?.nodes.map((n) => n.id).sort()).toEqual(['end', 'start', 'task-1']);
    const taskPos = layout?.nodes.find((n) => n.id === 'task-1');
    expect(taskPos).toEqual({ id: 'task-1', x: 250, y: 80 });
  });

  it('should_filter_stale_positions_when_nodes_removed_from_model', () => {
    const model = minimalModel();
    const positions = new Map([
      ['start', { x: 60, y: 80 }],
      ['end', { x: 300, y: 80 }],
      ['ghost', { x: 999, y: 999 }], // 已删除节点的残留坐标
    ]);
    const payload = buildSavePayload(model, positions);
    const layout = parseLayoutJson(payload.layoutJson) as { nodes: Array<{ id: string }> };
    expect(layout.nodes.map((n) => n.id).sort()).toEqual(['end', 'start']);
  });

  it('should_update_node_attributes_when_panel_edited', () => {
    // 属性面板回写模型（防抖回写的模型态终点）
    const model = minimalModel();
    model.nodes.push(node({ id: 'task-1', type: 'UserTask' }));
    const next = updateNode(model, 'task-1', {
      assignee: '${initiator}',
      candidateRoles: ['role-a'],
      assigneeStrategy: 'ALL',
    });
    expect(next.nodes.find((n) => n.id === 'task-1')?.assignee).toBe('${initiator}');
    expect(next.nodes.find((n) => n.id === 'task-1')?.candidateRoles).toEqual(['role-a']);
  });
});

// ————— AC-7：导入与布局缺省自动布局 —————

describe('REQ20261122023441 设计器导入与自动布局（AC-7）', () => {
  const importXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="${BPMN_MODEL_NS}" xmlns:acme="${ACME_EXT_NS}" targetNamespace="t">
  <process id="proc-import">
    <startEvent id="start"/>
    <sequenceFlow id="f0" sourceRef="start" targetRef="task-1"/>
    <userTask id="task-1" name="导入任务" acme:assignee="1001"/>
    <sequenceFlow id="f1" sourceRef="task-1" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;

  it('should_import_model_with_acme_attributes_when_xml_pasted', () => {
    const model = importModel(importXml);
    const task = model.nodes.find((n) => n.id === 'task-1');
    expect(task?.type).toBe('UserTask');
    expect(task?.assignee).toBe('1001');
    expect(model.edges.length).toBe(2);
  });

  it('should_auto_layout_every_node_when_layout_missing_on_import', () => {
    const model = importModel(importXml);
    const positions = autoLayout(model);
    expect(positions.size, '缺省布局时全节点应有坐标').toBe(model.nodes.length);
    for (const pos of positions.values()) {
      expect(Number.isFinite(pos.x)).toBe(true);
      expect(Number.isFinite(pos.y)).toBe(true);
    }
  });

  it('should_return_null_when_layout_json_is_missing_or_malformed', () => {
    // 布局 JSON 运行时校验：缺省/损坏/形状不符 → null（不入画布，不抛异常）
    expect(parseLayoutJson(null)).toBeNull();
    expect(parseLayoutJson('')).toBeNull();
    expect(parseLayoutJson('  ')).toBeNull();
    expect(parseLayoutJson('not-json')).toBeNull();
    expect(parseLayoutJson('{"nodes":"bad"}')).toBeNull();
    expect(parseLayoutJson('{"nodes":[{"id":"a"}]}')).toBeNull(); // 缺坐标
    expect(parseLayoutJson('{"nodes":[{"id":"","x":1,"y":2}]}')).toBeNull();
    expect(parseLayoutJson('42')).toBeNull();
    // 正例：合法形状解析回坐标
    const layout = parseLayoutJson(serializeLayout([{ id: 'a', x: 1.5, y: -2 }]));
    expect(layout).toEqual({ nodes: [{ id: 'a', x: 1.5, y: -2 }] });
  });
});

// ————— AC-11：版本对比（双栏文本 diff 自实现） —————

describe('REQ20261122023441 设计器版本对比（AC-11 模型面）', () => {
  it('should_diff_line_by_line_when_two_versions_compared', () => {
    const left = '<a/>\n<b/>\n<c/>';
    const right = '<a/>\n<x/>\n<c/>';
    const rows = diffLines(left, right);
    // 逐行对比：未变行 same，变更行按 del/add 两行分别呈现（4 行）
    expect(rows.length, '1 行变更=del+add 各一行，其余 same').toBe(4);
    expect(rows.filter((r) => r.op === 'same').length).toBe(2);
    expect(rows.filter((r) => r.op === 'del').length).toBe(1);
    expect(rows.filter((r) => r.op === 'add').length).toBe(1);
    expect(diffChangeCount(rows)).toBe(2);
  });

  it('should_report_all_same_when_contents_identical', () => {
    const rows = diffLines('a\nb', 'a\nb');
    expect(rows.length).toBe(2);
    expect(rows.every((r) => r.op === 'same')).toBe(true);
    expect(diffChangeCount(rows)).toBe(0);
  });
});
