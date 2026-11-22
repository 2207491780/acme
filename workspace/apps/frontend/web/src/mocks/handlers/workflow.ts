import { http, HttpResponse } from 'msw';
import * as db from '../data';
import { bodyOf, ok, page, pagingOf, slicePage, urlOf } from '../utils';
import { WORKFLOW_ERROR_CODES, type ProcessDefinitionView } from '@acme/core';

/**
 * 工作流域 handler（REQ-20261122-023441）：流程定义 6 端点，与后端契约同形——
 * wire id/version 字符串、PageResult 分页、错误码 01-0001~0005 两形态信封
 * （裸 message 信封 vs VALIDATION_FAILED 明细随 data）。列表 bpmnXml 置空。
 *
 * 演示触发（供测试/联调）：bpmnXml 含 __INVALID__ → 400 XML_INVALID；
 * 含 __VALIDATION_FAILED__ → 409 VALIDATION_FAILED + data 明细（nodeId+message）。
 */

const store = {
  definitions: [...db.workflowDefinitions],
};

let nextId = 9004;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();

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
];
