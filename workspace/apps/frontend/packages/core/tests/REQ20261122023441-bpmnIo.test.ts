// @vitest-environment jsdom
/**
 * REQ-20261122-023441 BPM-2——core bpmn-io 契约用例（AC-7/AC-8 载荷面）。
 *
 * 契约口径（contracts/REQ-20261122-023441.md 背景 §XML 约定 + 确认②⑥ + 设计 §5）：
 *   - 交换 = 标准 BPMN 2.0 XML（namespace omg MODEL）+ 扩展 ns http://acme.dev/bpmn/ext/v1 前缀 acme:；
 *     acme: 清单 = UserTask assignee/candidateRoles/candidateDepts/candidateUsers/assigneeStrategy(ALL)
 *     / ServiceTask serviceRef/callbackPath/retryPolicy / 节点级 executionListeners(before/after)
 *     / BoundaryEvent interrupting(attachedToRef 依附)。
 *   - 一期白名单 = StartEvent/EndEvent/XOR/AND/UserTask/ServiceTask/EmbeddedSubProcess/BoundaryEvent(timer,interrupting)；
 *     白名单外元素（CallActivity 等）= 二期 → 拒绝（BpmnXmlError）；非法 XML → 拒绝。
 *   - 静态校验规则集（设计 §5「校验规则」段，每规则一正一反；oracle=反例非空/正例清零，
 *     message 文案不钉死——pitfall 13/14 宽容口径）：
 *     R1 start/end 恰一；R2 无悬挂连线；R3 node id 唯一；R4 XOR 无 default 时流出全边带条件；
 *     R5 UserTask 至少一种指派；R6 ServiceTask 必填 serviceRef/callbackPath；
 *     R7 BoundaryEvent 必须挂接点；R8 gateway 出边≥2（AC-4）。
 *   - 导出（AC-8）：标准 BPMN 2.0 XML 含 acme: ns 声明；导入（AC-7）：acme: 属性回填建模。
 *
 * 装配：jsdom 文件内声明（parseBpmnXml 依赖 DOMParser）；纯逻辑用例不涉及组件交互。
 */
import { describe, expect, it } from 'vitest';
import {
  ACME_EXT_NS,
  BpmnXmlError,
  BPMN_MODEL_NS,
  parseBpmnXml,
  serializeBpmnModel,
  validateBpmnModel,
  type BpmnEdgeModel,
  type BpmnModel,
  type BpmnNodeModel,
} from '../src/workflow/bpmn-io';

// ————— 夹具工厂 —————

const node = (overrides: Partial<BpmnNodeModel> & { id: string; type: BpmnNodeModel['type'] }): BpmnNodeModel => ({
  name: '',
  executionListeners: [],
  ...overrides,
});

/** 最小合法模型：start → end（静态校验正例基线）。 */
const minimalModel = (): BpmnModel => ({
  nodes: [
    node({ id: 'start', type: 'StartEvent' }),
    node({ id: 'end', type: 'EndEvent' }),
  ],
  edges: [{ id: 'f0', sourceId: 'start', targetId: 'end' }],
});

/** 全元素互转矩阵夹具：8 个一期白名单元素各一，acme: 十属性全覆盖。 */
const fullMatrixModel = (): BpmnModel => ({
  nodes: [
    node({ id: 'start', type: 'StartEvent', name: '开始' }),
    node({
      id: 'user-1', type: 'UserTask', name: '审批',
      assignee: '${initiator}',
      candidateRoles: ['role-a', 'role-b'],
      candidateDepts: ['10', '20'],
      candidateUsers: ['1001'],
      assigneeStrategy: 'ALL',
      executionListeners: [{ event: 'before', ref: 'audit-listener' }, { event: 'after', ref: 'notify-listener' }],
    }),
    node({
      id: 'service-1', type: 'ServiceTask', name: '落库',
      serviceRef: 'acme-order-service', callbackPath: '/internal/order/confirm', retryPolicy: '3x1s',
      executionListeners: [{ event: 'after', ref: 'svc-after' }],
    }),
    node({ id: 'xor', type: 'XOR', name: '金额网关' }),
    node({ id: 'and', type: 'AND', name: '并行网关' }),
    node({ id: 'sub-1', type: 'EmbeddedSubProcess', name: '子流程' }),
    node({
      id: 'boundary-1', type: 'BoundaryEvent', name: '超时',
      interrupting: false, attachedToRef: 'user-1',
      executionListeners: [{ event: 'before', ref: 'timer-before' }],
    }),
    node({ id: 'end', type: 'EndEvent', name: '结束' }),
  ],
  edges: [
    { id: 'f0', sourceId: 'start', targetId: 'user-1' },
    { id: 'f1', sourceId: 'user-1', targetId: 'end' },
    { id: 'fx', sourceId: 'xor', targetId: 'service-1', condition: "${var('amount') >= 100}" },
    { id: 'fd', sourceId: 'xor', targetId: 'sub-1', isDefault: true },
    { id: 'fa', sourceId: 'and', targetId: 'boundary-1' },
    { id: 'fb', sourceId: 'boundary-1', targetId: 'end' },
  ] satisfies BpmnEdgeModel[],
});

/** 静态校验正例夹具：全元素契约合法流程（XOR 条件完备 + 全节点从 start 可达）。 */
const validProcessModel = (): BpmnModel => ({
  nodes: [
    node({ id: 'start', type: 'StartEvent' }),
    node({ id: 'user-1', type: 'UserTask', assignee: '1001', executionListeners: [] }),
    node({ id: 'xor', type: 'XOR' }),
    node({ id: 'service-1', type: 'ServiceTask', serviceRef: 'acme-svc', callbackPath: '/cb', executionListeners: [] }),
    node({ id: 'sub-1', type: 'EmbeddedSubProcess', executionListeners: [] }),
    node({ id: 'end', type: 'EndEvent' }),
  ],
  edges: [
    { id: 'f0', sourceId: 'start', targetId: 'user-1' },
    { id: 'f1', sourceId: 'user-1', targetId: 'xor' },
    { id: 'fx', sourceId: 'xor', targetId: 'service-1', condition: "${var('amount') >= 100}" },
    { id: 'fd', sourceId: 'xor', targetId: 'sub-1', isDefault: true },
    { id: 'fs', sourceId: 'service-1', targetId: 'end' },
    { id: 'fb', sourceId: 'sub-1', targetId: 'end' },
  ],
});

// ————— AC-8：导出（serializeBpmnModel） —————

describe('REQ20261122023441 bpmn-io 导出（AC-8）', () => {
  it('should_serialize_model_to_standard_bpmn_xml_when_model_exported', () => {
    const xml = serializeBpmnModel(fullMatrixModel());
    // 标准 BPMN 2.0 ns + acme: 扩展 ns 声明（AC-8：含 acme: ns 声明）
    expect(xml).toContain(BPMN_MODEL_NS);
    expect(xml).toContain(ACME_EXT_NS);
    expect(xml).toContain('<?xml');
    // 全元素在产物中可见（矩阵：每元素类型一行）
    for (const id of ['start', 'user-1', 'service-1', 'xor', 'and', 'sub-1', 'boundary-1', 'end']) {
      expect(xml).toContain(`"${id}"`);
    }
    // acme: 扩展属性透出（十属性代表面）
    expect(xml).toContain('acme:assignee="${initiator}"');
    expect(xml).toContain('acme:candidateRoles');
    expect(xml).toContain('acme:candidateDepts');
    expect(xml).toContain('acme:candidateUsers');
    expect(xml).toContain('acme:assigneeStrategy="ALL"');
    expect(xml).toContain('acme:serviceRef="acme-order-service"');
    expect(xml).toContain('acme:callbackPath="/internal/order/confirm"');
    expect(xml).toContain('acme:retryPolicy');
    expect(xml).toContain('acme:executionListeners');
    expect(xml).toContain('cancelActivity="false"'); // interrupting=false 语义
  });
});

// ————— 互转矩阵：serialize → parse 往返（AC-7/AC-8 同面） —————

describe('REQ20261122023441 bpmn-io 互转矩阵（AC-7）', () => {
  it('should_roundtrip_full_model_with_acme_attributes_when_serialized_then_parsed', () => {
    const source = fullMatrixModel();
    const roundtripped = parseBpmnXml(serializeBpmnModel(source));

    // 节点集与类型矩阵等价
    expect(roundtripped.nodes.map((n) => n.id).sort()).toEqual(source.nodes.map((n) => n.id).sort());
    const byId = new Map(roundtripped.nodes.map((n) => [n.id, n]));

    const user = byId.get('user-1');
    expect(user?.type).toBe('UserTask');
    expect(user?.assignee).toBe('${initiator}');
    expect(user?.candidateRoles).toEqual(['role-a', 'role-b']);
    expect(user?.candidateDepts).toEqual(['10', '20']);
    expect(user?.candidateUsers).toEqual(['1001']);
    expect(user?.assigneeStrategy).toBe('ALL');
    expect(user?.executionListeners).toEqual([
      { event: 'before', ref: 'audit-listener' },
      { event: 'after', ref: 'notify-listener' },
    ]);

    const service = byId.get('service-1');
    expect(service?.type).toBe('ServiceTask');
    expect(service?.serviceRef).toBe('acme-order-service');
    expect(service?.callbackPath).toBe('/internal/order/confirm');
    expect(service?.retryPolicy).toBe('3x1s');

    const boundary = byId.get('boundary-1');
    expect(boundary?.type).toBe('BoundaryEvent');
    expect(boundary?.attachedToRef).toBe('user-1');
    expect(boundary?.interrupting).toBe(false);

    expect(byId.get('xor')?.type).toBe('XOR');
    expect(byId.get('and')?.type).toBe('AND');
    expect(byId.get('sub-1')?.type).toBe('EmbeddedSubProcess');
    expect(byId.get('start')?.type).toBe('StartEvent');
    expect(byId.get('end')?.type).toBe('EndEvent');

    // 连线：条件表达式与 default 流往返保持
    const byEdgeId = new Map(roundtripped.edges.map((e) => [e.id, e]));
    expect(byEdgeId.get('fx')?.condition).toBe("${var('amount') >= 100}");
    expect(byEdgeId.get('fd')?.isDefault).toBe(true);
    expect(byEdgeId.get('f0')?.sourceId).toBe('start');
    expect(byEdgeId.get('f0')?.targetId).toBe('user-1');
  });

  it('should_backfill_acme_attributes_when_importing_third_party_xml', () => {
    // AC-7：导入合法 BPMN XML（默认命名空间书写风格，非本序列化器产物）→ acme: 属性回填
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="${BPMN_MODEL_NS}"
             xmlns:acme="${ACME_EXT_NS}"
             targetNamespace="http://acme.dev/bpmn/it">
  <process id="proc-import">
    <startEvent id="start"/>
    <sequenceFlow id="f0" sourceRef="start" targetRef="task-1"/>
    <userTask id="task-1" name="导入审批" acme:assignee="1001" acme:candidateRoles="role-a,role-b"/>
    <sequenceFlow id="f1" sourceRef="task-1" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;
    const model = parseBpmnXml(xml);
    const task = model.nodes.find((n) => n.id === 'task-1');
    expect(task?.type).toBe('UserTask');
    expect(task?.assignee).toBe('1001');
    expect(task?.candidateRoles).toEqual(['role-a', 'role-b']);
    expect(model.nodes.some((n) => n.id === 'start')).toBe(true);
    expect(model.edges.some((e) => e.id === 'f0' && e.sourceId === 'start' && e.targetId === 'task-1')).toBe(true);
  });
});

// ————— 白名单外拒绝 / 非法 XML 拒绝（AC-7 反例） —————

describe('REQ20261122023441 bpmn-io 白名单与非法 XML（AC-7）', () => {
  const expectXmlError = (xml: string, messageKey: RegExp, element?: string): void => {
    try {
      parseBpmnXml(xml);
      throw new Error(`应拒绝非法 XML（${messageKey}），实际成功解析`);
    } catch (ex: unknown) {
      expect(ex, '应抛 BpmnXmlError').toBeInstanceOf(BpmnXmlError);
      const err = ex as BpmnXmlError;
      expect(err.messageKey).toMatch(messageKey);
      if (element !== undefined) {
        expect(err.element ?? '').toContain(element);
      }
    }
  };

  it('should_reject_element_outside_phase1_whitelist_when_importing', () => {
    // 白名单外（CallActivity=二期）→ elementNotWhitelisted
    expectXmlError(
      `<?xml version="1.0"?><definitions xmlns="${BPMN_MODEL_NS}" xmlns:acme="${ACME_EXT_NS}" targetNamespace="t">
        <process id="p"><startEvent id="s"/><callActivity id="ca" calledElement="other"/><endEvent id="e"/></process>
      </definitions>`,
      /whitelist/i,
      'callActivity',
    );
  });

  it('should_reject_when_xml_is_empty_or_malformed_or_non_bpmn', () => {
    expectXmlError('', /empty/i);
    expectXmlError('   \n  ', /empty/i);
    // 非 XML / 畸形 XML
    expectXmlError('this is not xml at all', /malformed|notDefinitions/i);
    expectXmlError(
      `<?xml version="1.0"?><definitions xmlns="${BPMN_MODEL_NS}" targetNamespace="t"><process id="p">`,
      /malformed|mismatchedTag/i,
    );
    // 根元素非 definitions
    expectXmlError('<?xml version="1.0"?><foo xmlns="urn:x"/>', /notDefinitions/i);
    // 无 process 元素
    expectXmlError(
      `<?xml version="1.0"?><definitions xmlns="${BPMN_MODEL_NS}" targetNamespace="t"/>`,
      /noProcess/i,
    );
  });
});

// ————— 静态校验规则集（每规则一正一反；oracle=反例非空/正例清零） —————

describe('REQ20261122023441 bpmn-io 静态校验规则集（AC-9 规则面）', () => {
  /** 反例 oracle：注入单一违规后模型其余部分合法 → 错误非空即规则生效。 */
  const expectRuleViolation = (broken: BpmnModel, fixed: BpmnModel): void => {
    expect(validateBpmnModel(broken).length,
      '违规模型应报出问题（反例非空）').toBeGreaterThanOrEqual(1);
    expect(validateBpmnModel(fixed),
      '修复后模型应完全通过（正例清零）').toEqual([]);
  };

  it('should_report_violation_when_start_or_end_count_is_not_exactly_one', () => {
    // R1 反例：双 start（多出的 start 未连线，其余合法）；正例：恢复单 start
    const broken = minimalModel();
    broken.nodes.push(node({ id: 'start-2', type: 'StartEvent' }));
    expectRuleViolation(broken, minimalModel());

    // R1 反例二：无 end
    const noEnd = minimalModel();
    noEnd.nodes = noEnd.nodes.filter((n) => n.type !== 'EndEvent');
    noEnd.edges = [];
    expect(validateBpmnModel(noEnd).length).toBeGreaterThanOrEqual(1);
  });

  it('should_report_violation_when_edge_has_dangling_endpoint', () => {
    // R2 反例：连线指向不存在节点；正例：连线完整
    const broken = minimalModel();
    broken.edges.push({ id: 'f-ghost', sourceId: 'end', targetId: 'ghost-node' });
    expectRuleViolation(broken, minimalModel());
  });

  it('should_report_violation_when_node_ids_are_duplicated', () => {
    // R3 反例：id 重复；正例：唯一
    const broken = minimalModel();
    broken.nodes.push(node({ id: 'end', type: 'EndEvent' }));
    expectRuleViolation(broken, minimalModel());
  });

  it('should_report_violation_when_xor_outgoing_edges_miss_conditions_without_default', () => {
    // R4 反例：XOR 无 default 且流出边缺条件；正例：全边带条件（或 default 标注）
    const base = (): BpmnModel => ({
      nodes: [
        node({ id: 'start', type: 'StartEvent' }),
        node({ id: 'xor', type: 'XOR' }),
        node({ id: 'task-a', type: 'UserTask', assignee: '1001', executionListeners: [] }),
        node({ id: 'task-b', type: 'UserTask', assignee: '1001', executionListeners: [] }),
        node({ id: 'end', type: 'EndEvent' }),
      ],
      edges: [
        { id: 'f0', sourceId: 'start', targetId: 'xor' },
        { id: 'g1', sourceId: 'xor', targetId: 'task-a' },
        { id: 'g2', sourceId: 'xor', targetId: 'task-b' },
        { id: 'f1', sourceId: 'task-a', targetId: 'end' },
        { id: 'f2', sourceId: 'task-b', targetId: 'end' },
      ],
    });
    const fixed = base();
    fixed.edges = fixed.edges.map((e) => (e.id === 'g1' ? { ...e, condition: "${var('amount') >= 100}" } : e));
    fixed.edges = fixed.edges.map((e) => (e.id === 'g2' ? { ...e, isDefault: true } : e));
    expectRuleViolation(base(), fixed);
  });

  it('should_report_violation_when_user_task_has_no_assignment_rule', () => {
    // R5 反例：UserTask 无任何指派；正例：assignee 指派
    const broken = minimalModel();
    broken.nodes.push(node({ id: 'task-1', type: 'UserTask' }));
    broken.edges.push({ id: 'f1', sourceId: 'start', targetId: 'task-1' });
    const fixed = minimalModel();
    fixed.nodes.push(node({ id: 'task-1', type: 'UserTask', assignee: '1001' }));
    fixed.edges.push({ id: 'f1', sourceId: 'start', targetId: 'task-1' });
    const violations = validateBpmnModel(broken);
    expect(violations.length).toBeGreaterThanOrEqual(1);
    expect(violations.some((v) => v.nodeId === 'task-1'), '违规应定位到未指派任务').toBe(true);
    expect(validateBpmnModel(fixed)).toEqual([]);
  });

  it('should_report_violation_when_service_task_misses_required_attributes', () => {
    // R6 反例：ServiceTask 缺 serviceRef/callbackPath；正例：两属性齐备
    const broken = minimalModel();
    broken.nodes.push(node({ id: 'svc-1', type: 'ServiceTask', callbackPath: '/cb' }));
    broken.edges.push({ id: 'f1', sourceId: 'start', targetId: 'svc-1' });
    const fixed = minimalModel();
    fixed.nodes.push(node({
      id: 'svc-1', type: 'ServiceTask',
      serviceRef: 'acme-svc', callbackPath: '/cb',
    }));
    fixed.edges.push({ id: 'f1', sourceId: 'start', targetId: 'svc-1' });
    const violations = validateBpmnModel(broken);
    expect(violations.length).toBeGreaterThanOrEqual(1);
    expect(violations.some((v) => v.nodeId === 'svc-1'), '违规应定位到缺属性服务任务').toBe(true);
    expect(validateBpmnModel(fixed)).toEqual([]);
  });

  it('should_report_violation_when_boundary_event_is_not_attached', () => {
    // R7 反例：BoundaryEvent 未挂接点；正例：attachedToRef 依附任务
    const attached = (): BpmnModel => ({
      nodes: [
        node({ id: 'start', type: 'StartEvent' }),
        node({ id: 'task-1', type: 'UserTask', assignee: '1001', executionListeners: [] }),
        node({ id: 'boundary-1', type: 'BoundaryEvent', interrupting: true, attachedToRef: 'task-1', executionListeners: [] }),
        node({ id: 'end', type: 'EndEvent' }),
      ],
      edges: [
        { id: 'f0', sourceId: 'start', targetId: 'task-1' },
        { id: 'f1', sourceId: 'task-1', targetId: 'end' },
        { id: 'fb', sourceId: 'boundary-1', targetId: 'end' },
      ],
    });
    const broken = attached();
    const boundary = broken.nodes.find((n) => n.id === 'boundary-1') as BpmnNodeModel;
    boundary.attachedToRef = null;
    const violations = validateBpmnModel(broken);
    expect(violations.length).toBeGreaterThanOrEqual(1);
    expect(violations.some((v) => v.nodeId === 'boundary-1'), '违规应定位到未依附边界事件').toBe(true);
    expect(validateBpmnModel(attached())).toEqual([]);
  });

  it('should_allow_single_conditioned_outgoing_edge_when_gateway_condition_complete', () => {
    // R8（TL 裁决改写：出边≥2 规则删除）——正例：XOR 单出边 + 条件完备（default 流）应通过
    const singleDefault = minimalModel();
    singleDefault.nodes.push(node({ id: 'xor', type: 'XOR' }));
    singleDefault.nodes.push(node({ id: 'task-1', type: 'UserTask', assignee: '1001', executionListeners: [] }));
    singleDefault.edges.push({ id: 'f1', sourceId: 'start', targetId: 'xor' });
    singleDefault.edges.push({ id: 'f2', sourceId: 'xor', targetId: 'end', isDefault: true });
    singleDefault.edges.push({ id: 'f3', sourceId: 'start', targetId: 'task-1' });
    singleDefault.edges.push({ id: 'f4', sourceId: 'xor', targetId: 'task-1', condition: "${var('x') > 1}" });
    expect(validateBpmnModel(singleDefault)).toEqual([]);

    // 反例：XOR 流出无条件且无 default → 仍拒（与 R4 条件完备同口径）
    const unconditional = minimalModel();
    unconditional.nodes.push(node({ id: 'xor', type: 'XOR' }));
    unconditional.edges.push({ id: 'f1', sourceId: 'start', targetId: 'xor' });
    unconditional.edges.push({ id: 'f2', sourceId: 'xor', targetId: 'end' });
    const violations = validateBpmnModel(unconditional);
    expect(violations.length).toBeGreaterThanOrEqual(1);
    expect(violations.some((v) => v.nodeId === 'xor'), '违规应定位到条件不完备网关').toBe(true);
  });

  it('should_pass_clean_model_without_violations_when_validation_runs', () => {
    expect(validateBpmnModel(minimalModel())).toEqual([]);
    // 契约合法全元素流程（XOR 条件完备/default、网关出边≥2、全节点从 start 可达）
    expect(validateBpmnModel(validProcessModel())).toEqual([]);
  });
});
