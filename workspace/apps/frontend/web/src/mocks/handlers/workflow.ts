import { http, HttpResponse } from 'msw';
import * as db from '../data';
import { bodyOf, empty, ok, page, pagingOf, slicePage, urlOf } from '../utils';
import {
  WORKFLOW_ERROR_CODES,
  type HistoryActivityView,
  type ProcessDefinitionView,
  type ProcessInstanceView,
  type TaskView,
} from '@acme/core';

/**
 * 工作流域 handler（REQ-20261122-023441）：流程定义 6 端点，与后端契约同形——
 * wire id/version 字符串、PageResult 分页、错误码 01-0001~0005 两形态信封
 * （裸 message 信封 vs VALIDATION_FAILED 明细随 data）。列表 bpmnXml 置空。
 *
 * REQ-20261122-102730（BPM-3）：扩实例/任务域端点（契约 apis 面）——
 * 实例分页/我发起/详情/历史/发起/终止/挂起/恢复 + 任务 todo/done/complete/reject/
 * claim/unclaim/transfer/delegate。wire id 一律字符串；todo/done/initiated 行带
 * definitionKey/definitionName/businessKey/initiator 投影（契约⑤，前端禁 N+1）。
 *
 * 演示触发（供测试/联调）：bpmnXml 含 __INVALID__ → 400 XML_INVALID；
 * 含 __VALIDATION_FAILED__ → 409 VALIDATION_FAILED + data 明细（nodeId+message）。
 */

const store = {
  definitions: [...db.workflowDefinitions],
  instances: [...db.workflowInstances],
  tasks: [...db.workflowTasks],
  histories: { ...db.workflowHistories } as Record<string, HistoryActivityView[]>,
};

/** mock 当前登录人（待办/我发起投影与任务动作 assignee 口径，对齐 data.me）。 */
const ME_USERNAME = db.me.user.username;

let nextId = 9004;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();

/** 实例 id 序列（fixture 至 9305；新发起实例自 9310 起，避免与种子段冲突）。 */
let nextInstanceId = 9310;
const genInstanceId = (): string => String(nextInstanceId++);

/** 任务 id 序列（fixture 至 9403；新任务自 9410 起）。 */
let nextTaskId = 9410;
const genTaskId = (): string => String(nextTaskId++);

/** 历史条目 id 序列（fixture 至 9619；新条目自 9630 起）。 */
let nextHistoryId = 9630;
const genHistoryId = (): string => String(nextHistoryId++);

/** 失败信封 + data 载荷（VALIDATION_FAILED 明细形态；utils.fail 固定 data=null 不适用）。 */
function failWithData(code: string, message: string, status: number, data: unknown) {
  return HttpResponse.json({ success: false, code, message, data }, { status });
}

/** 列表投影（契约：列表 bpmnXml 置空）。 */
function toListRow(view: ProcessDefinitionView): ProcessDefinitionView {
  return { ...view, bpmnXml: null };
}

/** 新建草稿视图（Create：key/name → DRAFT v1）。 */
function draftFrom(key: string, name: string): ProcessDefinitionView {
  return {
    id: genId(),
    key,
    name,
    version: '1',
    status: 'DRAFT',
    bpmnXml: null,
    layoutJson: null,
    createdAt: nowIso(),
    deployedAt: null,
  };
}

/** 校验明细演示数据（节点级+模型级各一条，nodeId 可空形态对齐契约）。 */
function validationDetail(): Array<{ nodeId: string | null; field: string | null; message: string }> {
  return [
    { nodeId: 'task-1', field: null, message: '用户任务缺少指派（assignee 或候选角色/部门/用户）' },
    { nodeId: null, field: null, message: '流程缺少结束事件' },
  ];
}

/** 任务存在性判定（82 动作共用；不存在=404 NOT_FOUND）。 */
function findTask(taskId: string): TaskView | undefined {
  return store.tasks.find((row) => row.id === taskId);
}

export const workflowHandlers = [
  // 1) GET 列表：keyword（key/name 模糊）/status 精确 + 分页
  http.get('/admin/v1/workflow/process-definitions', ({ request }) => {
    const params = urlOf(request).searchParams;
    const keyword = params.get('keyword');
    const status = params.get('status');
    const rows = store.definitions.filter((row) => {
      if (keyword) {
        const needle = keyword.toLowerCase();
        if (!row.key.toLowerCase().includes(needle) && !row.name.toLowerCase().includes(needle)) return false;
      }
      if (status && row.status !== status) return false;
      return true;
    });
    return page(slicePage(rows.map(toListRow), pagingOf(request)), rows.length);
  }),

  // 2) POST 新建：key 重复 → 409 KEY_DUPLICATE；成功返回草稿视图
  http.post('/admin/v1/workflow/process-definitions', async ({ request }) => {
    const body = await bodyOf(request);
    const key = typeof body.key === 'string' ? body.key.trim() : '';
    const name = typeof body.name === 'string' ? body.name.trim() : '';
    if (!key || !name) {
      return failWithData(WORKFLOW_ERROR_CODES.XML_INVALID, 'key/name 不能为空', 400, null);
    }
    if (store.definitions.some((row) => row.key === key)) {
      return failWithData(WORKFLOW_ERROR_CODES.KEY_DUPLICATE, `流程标识 ${key} 已存在`, 409, null);
    }
    const created = draftFrom(key, name);
    store.definitions = [created, ...store.definitions];
    return ok(created);
  }),

  // 3) GET 详情：全量视图（含 bpmnXml/layoutJson 回显）
  http.get('/admin/v1/workflow/process-definitions/:definitionId', ({ params }) => {
    const target = store.definitions.find((row) => row.id === String(params.definitionId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程定义不存在', 404, null);
    }
    return ok(target);
  }),

  // 4) PUT xml：仅草稿态；409 明细随 data；演示触发 __INVALID__ / __VALIDATION_FAILED__
  http.put('/admin/v1/workflow/process-definitions/:definitionId/xml', async ({ request, params }) => {
    const target = store.definitions.find((row) => row.id === String(params.definitionId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程定义不存在', 404, null);
    }
    if (target.status !== 'DRAFT') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅草稿态可保存', 409, null);
    }
    const body = await bodyOf(request);
    const bpmnXml = typeof body.bpmnXml === 'string' ? body.bpmnXml : '';
    if (!bpmnXml.trim()) {
      return failWithData(WORKFLOW_ERROR_CODES.XML_INVALID, 'bpmnXml 不能为空', 400, null);
    }
    if (bpmnXml.includes('__INVALID__')) {
      return failWithData(WORKFLOW_ERROR_CODES.XML_INVALID, 'XML 解析失败', 400, null);
    }
    if (bpmnXml.includes('__VALIDATION_FAILED__')) {
      return failWithData(WORKFLOW_ERROR_CODES.VALIDATION_FAILED, '流程校验未通过', 409, validationDetail());
    }
    const layoutJson = typeof body.layoutJson === 'string' && body.layoutJson.trim() !== '' ? body.layoutJson : null;
    store.definitions = store.definitions.map((row) =>
      row.id === target.id ? { ...row, bpmnXml, layoutJson } : row,
    );
    return ok({ ...target, bpmnXml, layoutJson });
  }),

  // 5) POST deploy：DISABLED 不可部署；校验失败 409 明细随 data；成功转 DEPLOYED
  http.post('/admin/v1/workflow/process-definitions/:definitionId/deploy', ({ params }) => {
    const target = store.definitions.find((row) => row.id === String(params.definitionId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程定义不存在', 404, null);
    }
    if (target.status === 'DISABLED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '已停用定义不可部署', 409, null);
    }
    if (target.bpmnXml?.includes('__VALIDATION_FAILED__')) {
      return failWithData(WORKFLOW_ERROR_CODES.VALIDATION_FAILED, '流程校验未通过', 409, validationDetail());
    }
    const deployed: ProcessDefinitionView = { ...target, status: 'DEPLOYED', deployedAt: nowIso() };
    store.definitions = store.definitions.map((row) => (row.id === target.id ? deployed : row));
    return ok(deployed);
  }),

  // 6) POST disable：仅已部署可停用；成功转 DISABLED
  http.post('/admin/v1/workflow/process-definitions/:definitionId/disable', ({ params }) => {
    const target = store.definitions.find((row) => row.id === String(params.definitionId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程定义不存在', 404, null);
    }
    if (target.status !== 'DEPLOYED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅已部署定义可停用', 409, null);
    }
    const disabled: ProcessDefinitionView = { ...target, status: 'DISABLED' };
    store.definitions = store.definitions.map((row) => (row.id === target.id ? disabled : row));
    return ok(disabled);
  }),

  // ————— 实例域（BPM-3；registered 顺序：initiated 必须先于 /:id 字面量） —————

  // 7) GET 实例分页：definitionKey/status/businessKey 精确过滤（80 读面）
  http.get('/admin/v1/workflow/process-instances', ({ request }) => {
    const params = urlOf(request).searchParams;
    const definitionKey = params.get('definitionKey');
    const status = params.get('status');
    const businessKey = params.get('businessKey');
    const rows = store.instances.filter((row) => {
      if (definitionKey && row.definitionKey !== definitionKey) return false;
      if (status && row.status !== status) return false;
      if (businessKey && row.businessKey !== businessKey) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),

  // 8) GET 我发起：initiator=mock 登录人（待办中心「我发起」子区）
  http.get('/admin/v1/workflow/process-instances/initiated', ({ request }) => {
    const rows = store.instances.filter((row) => row.initiator === ME_USERNAME);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),

  // 9) GET 实例详情：variables/activeNodes/tasks 全量回显
  http.get('/admin/v1/workflow/process-instances/:instanceId', ({ params }) => {
    const target = store.instances.find((row) => row.id === String(params.instanceId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程实例不存在', 404, null);
    }
    return ok(target);
  }),

  // 10) GET 历史活动：非分页 List（缺省=空数组，前端 fail-soft 空态）
  http.get('/admin/v1/workflow/process-instances/:instanceId/history', ({ params }) => {
    if (!store.instances.some((row) => row.id === String(params.instanceId))) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程实例不存在', 404, null);
    }
    return ok(store.histories[String(params.instanceId)] ?? []);
  }),

  // 11) POST 发起：定义须 DEPLOYED；RUNNING 落库并按定义首个 userTask 生成 CREATED 任务
  http.post('/admin/v1/workflow/process-instances', async ({ request }) => {
    const body = await bodyOf(request);
    const definitionId = typeof body.definitionId === 'string' ? body.definitionId : '';
    const definition = store.definitions.find((row) => row.id === definitionId);
    if (!definition) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程定义不存在', 404, null);
    }
    if (definition.status !== 'DEPLOYED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '流程定义未部署，不可发起', 409, null);
    }
    const businessKey = typeof body.businessKey === 'string' && body.businessKey !== '' ? body.businessKey : null;
    const variables = (typeof body.variables === 'object' && body.variables !== null ? body.variables : null) as Record<string, unknown> | null;
    const instanceId = genInstanceId();
    const startedAt = nowIso();
    // 首个 userTask → activeNodes + CREATED 任务（无用户任务的流程仅落实例，fail-soft）
    const match = /<bpmn:userTask\b[^>]*id="([^"]+)"[^>]*?(?:name="([^"]*)")?[^>]*\/?>/.exec(definition.bpmnXml ?? '');
    const firstTaskNodeId = match?.[1] ?? null;
    const firstTaskNodeName = match?.[2] || firstTaskNodeId;
    const instance: ProcessInstanceView = {
      id: instanceId,
      definitionId: definition.id,
      definitionKey: definition.key,
      definitionName: definition.name,
      businessKey,
      initiator: ME_USERNAME,
      status: 'RUNNING',
      suspendReason: null,
      startTime: startedAt,
      endTime: null,
      variables,
      activeNodes: firstTaskNodeId
        ? [{ nodeId: firstTaskNodeId, nodeName: firstTaskNodeName ?? null, nodeType: 'UserTask', enterAt: startedAt }]
        : [],
      tasks: [],
    };
    if (firstTaskNodeId) {
      const task: TaskView = {
        id: genTaskId(),
        instanceId,
        nodeId: firstTaskNodeId,
        nodeName: firstTaskNodeName ?? null,
        assignee: null,
        status: 'CREATED',
        claimTime: null,
        completeTime: null,
        createTime: startedAt,
        definitionKey: definition.key,
        definitionName: definition.name,
        businessKey,
        initiator: ME_USERNAME,
      };
      store.tasks = [task, ...store.tasks];
      instance.tasks = [task];
    }
    store.instances = [instance, ...store.instances];
    store.histories = {
      ...store.histories,
      [instanceId]: [
        { id: genHistoryId(), nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: startedAt, endAt: null, detail: null },
      ],
    };
    return ok(instance);
  }),

  // 12) POST 终止：仅 RUNNING；TERMINATED + endTime 落定
  http.post('/admin/v1/workflow/process-instances/:instanceId/terminate', ({ params }) => {
    const target = store.instances.find((row) => row.id === String(params.instanceId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程实例不存在', 404, null);
    }
    if (target.status !== 'RUNNING') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅运行中实例可终止', 409, null);
    }
    const terminated: ProcessInstanceView = { ...target, status: 'TERMINATED', endTime: nowIso(), activeNodes: [], tasks: [] };
    store.instances = store.instances.map((row) => (row.id === target.id ? terminated : row));
    return empty();
  }),

  // 13) POST 挂起：仅 RUNNING；suspendReason=body.reason
  http.post('/admin/v1/workflow/process-instances/:instanceId/suspend', async ({ request, params }) => {
    const target = store.instances.find((row) => row.id === String(params.instanceId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程实例不存在', 404, null);
    }
    if (target.status !== 'RUNNING') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅运行中实例可挂起', 409, null);
    }
    const body = await bodyOf(request);
    const reason = typeof body.reason === 'string' && body.reason !== '' ? body.reason : null;
    store.instances = store.instances.map((row) =>
      row.id === target.id ? { ...row, status: 'SUSPENDED', suspendReason: reason } : row,
    );
    return empty();
  }),

  // 14) POST 恢复：仅 SUSPENDED；suspendReason 清空
  http.post('/admin/v1/workflow/process-instances/:instanceId/resume', ({ params }) => {
    const target = store.instances.find((row) => row.id === String(params.instanceId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '流程实例不存在', 404, null);
    }
    if (target.status !== 'SUSPENDED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅已挂起实例可恢复', 409, null);
    }
    store.instances = store.instances.map((row) =>
      row.id === target.id ? { ...row, status: 'RUNNING', suspendReason: null } : row,
    );
    return empty();
  }),

  // ————— 任务域（BPM-3；82 写面动作，todo/done 行带契约⑤投影） —————

  // 15) GET 我的待办：CREATED（assignee=登录人 或 候选可见 assignee=null）/CLAIMED（assignee=登录人）；
  //     definitionKey/status 过滤（契约⑤；候选 ALL 策略可见可办=AC-6 claim 前置）
  http.get('/admin/v1/workflow/tasks/todo', ({ request }) => {
    const params = urlOf(request).searchParams;
    const definitionKey = params.get('definitionKey');
    const status = params.get('status');
    const rows = store.tasks.filter((row) => {
      if (row.status !== 'CREATED' && row.status !== 'CLAIMED') return false;
      if (row.assignee !== ME_USERNAME && !(row.status === 'CREATED' && row.assignee === null)) return false;
      if (definitionKey && row.definitionKey !== definitionKey) return false;
      if (status && row.status !== status) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),

  // 16) GET 我已办：COMPLETED/REJECTED 且 assignee=登录人；同 todo 过滤口径
  http.get('/admin/v1/workflow/tasks/done', ({ request }) => {
    const params = urlOf(request).searchParams;
    const definitionKey = params.get('definitionKey');
    const status = params.get('status');
    const rows = store.tasks.filter((row) => {
      if (row.status !== 'COMPLETED' && row.status !== 'REJECTED') return false;
      if (row.assignee !== ME_USERNAME) return false;
      if (definitionKey && row.definitionKey !== definitionKey) return false;
      if (status && row.status !== status) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),

  // 17) POST 办结：CREATED/CLAIMED → COMPLETED + completeTime
  http.post('/admin/v1/workflow/tasks/:taskId/complete', async ({ request, params }) => {
    const target = findTask(String(params.taskId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '任务不存在', 404, null);
    }
    if (target.status !== 'CREATED' && target.status !== 'CLAIMED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅待办任务可办结', 409, null);
    }
    await bodyOf(request);
    store.tasks = store.tasks.map((row) =>
      row.id === target.id ? { ...row, status: 'COMPLETED', completeTime: nowIso() } : row,
    );
    return empty();
  }),

  // 18) POST 驳回：→ REJECTED + completeTime；targetNodeId 非法=400 XML_INVALID
  http.post('/admin/v1/workflow/tasks/:taskId/reject', async ({ request, params }) => {
    const target = findTask(String(params.taskId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '任务不存在', 404, null);
    }
    if (target.status !== 'CREATED' && target.status !== 'CLAIMED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅待办任务可驳回', 409, null);
    }
    const body = await bodyOf(request);
    const targetNodeId = typeof body.targetNodeId === 'string' ? body.targetNodeId : null;
    if (targetNodeId) {
      const known = (store.histories[target.instanceId] ?? []).some((row) => row.nodeId === targetNodeId && row.action === 'ENTER')
        || targetNodeId === 'start-1';
      if (!known) {
        return failWithData(WORKFLOW_ERROR_CODES.XML_INVALID, `驳回目标节点 ${targetNodeId} 不存在`, 400, null);
      }
    }
    store.tasks = store.tasks.map((row) =>
      row.id === target.id ? { ...row, status: 'REJECTED', completeTime: nowIso() } : row,
    );
    return empty();
  }),

  // 19) POST 认领：CREATED → CLAIMED（assignee=登录人）
  http.post('/admin/v1/workflow/tasks/:taskId/claim', ({ params }) => {
    const target = findTask(String(params.taskId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '任务不存在', 404, null);
    }
    if (target.status !== 'CREATED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅待认领任务可认领', 409, null);
    }
    store.tasks = store.tasks.map((row) =>
      row.id === target.id
        ? { ...row, status: 'CLAIMED', assignee: ME_USERNAME, claimTime: nowIso() }
        : row,
    );
    return empty();
  }),

  // 20) POST 取消认领：CLAIMED → CREATED（assignee/claimTime 清空）
  http.post('/admin/v1/workflow/tasks/:taskId/unclaim', ({ params }) => {
    const target = findTask(String(params.taskId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '任务不存在', 404, null);
    }
    if (target.status !== 'CLAIMED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅已认领任务可取消认领', 409, null);
    }
    store.tasks = store.tasks.map((row) =>
      row.id === target.id ? { ...row, status: 'CREATED', assignee: null, claimTime: null } : row,
    );
    return empty();
  }),

  // 21) POST 转办：assignee 变更（CREATED/CLAIMED 均可）
  http.post('/admin/v1/workflow/tasks/:taskId/transfer', async ({ request, params }) => {
    const target = findTask(String(params.taskId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '任务不存在', 404, null);
    }
    if (target.status !== 'CREATED' && target.status !== 'CLAIMED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅待办任务可转办', 409, null);
    }
    const body = await bodyOf(request);
    const assignee = typeof body.assignee === 'string' ? body.assignee.trim() : '';
    if (!assignee) {
      return failWithData(WORKFLOW_ERROR_CODES.XML_INVALID, 'assignee 不能为空', 400, null);
    }
    store.tasks = store.tasks.map((row) =>
      row.id === target.id ? { ...row, assignee } : row,
    );
    return empty();
  }),

  // 22) POST 委托：委托链单层（assignee 变更；语义与 transfer 区分由后端审计面承担）
  http.post('/admin/v1/workflow/tasks/:taskId/delegate', async ({ request, params }) => {
    const target = findTask(String(params.taskId));
    if (!target) {
      return failWithData(WORKFLOW_ERROR_CODES.NOT_FOUND, '任务不存在', 404, null);
    }
    if (target.status !== 'CREATED' && target.status !== 'CLAIMED') {
      return failWithData(WORKFLOW_ERROR_CODES.DISABLED, '仅待办任务可委托', 409, null);
    }
    const body = await bodyOf(request);
    const assignee = typeof body.assignee === 'string' ? body.assignee.trim() : '';
    if (!assignee) {
      return failWithData(WORKFLOW_ERROR_CODES.XML_INVALID, 'assignee 不能为空', 400, null);
    }
    store.tasks = store.tasks.map((row) =>
      row.id === target.id ? { ...row, assignee } : row,
    );
    return empty();
  }),
];
