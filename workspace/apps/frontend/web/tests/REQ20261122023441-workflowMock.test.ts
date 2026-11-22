// @vitest-environment jsdom
/**
 * REQ-20261122-023441 BPM-2——msw 六端点同形契约用例（AC-13 + AC-7/8 载荷面）。
 *
 * 契约口径（contracts/REQ-20261122-023441.md 背景「后端 API 实际形状」+ TL 钉死事实）：
 *   - wire id/version=字符串；PageResult 分页（records/total，照 core 既有分页类型惯例）；
 *   - 列表 bpmnXml 置空；详情全量回显 bpmnXml/layoutJson；
 *   - 错误码 01-0001 KEY_DUPLICATE(409)/01-0002 XML_INVALID(400)/01-0003 VALIDATION_FAILED(409 明细随 data)
 *     /01-0004 NOT_FOUND(404)/01-0005 DISABLED(409)；两形态信封（裸 message vs data 明细）；
 *   - 保存载荷（AC-6/7/8）：PUT body 含 bpmnXml+layoutJson 单请求；仅草稿态可保存；
 *     部署校验失败 409 明细经 workflowValidationErrorsOf 装配（AC-10 装配面）。
 *
 * MSW 接入口径照 web/tests/REQ20261121022910-packagesMock.test.ts 先例：复用交付 handlers，
 * msw/node setupServer + onUnhandledRequest:'error'；错误码精确断言（wire 码契约钉死）。
 * 隔离纪律：fixture 9001~9003 只读不写（状态变更会跨用例残留）；写面用例一律自建唯一 key 定义。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  ApiError,
  workflowApi,
  workflowValidationErrorsOf,
  WORKFLOW_ENDPOINTS,
  WORKFLOW_ERROR_CODES,
  useAuthStore,
} from '@acme/core';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

/** 唯一 key（跨用例不撞，mock store 无删除端点——teardown 以唯一性隔离替代）。 */
let seq = 0;
const uniqKey = (suffix: string): string => `bpm2it-${Date.now()}-${++seq}-${suffix}`;

/** ApiError 拆包（业务失败信封 → 异常）：负例断言走错误码精确匹配（TL 钉死事实面）。 */
const expectApiError = async (invoke: () => Promise<unknown>, code: string): Promise<ApiError> => {
  try {
    await invoke();
    throw new Error(`应抛出业务失败（${code}），实际成功返回`);
  } catch (ex: unknown) {
    expect(ex, `应抛 ApiError（${code}）`).toBeInstanceOf(ApiError);
    const err = ex as ApiError;
    expect(err.code).toBe(code);
    return err;
  }
};

/** 最小合法 BPMN XML（保存载荷夹具；可注入演示触发标记）。 */
const bpmnXml = (marker = ''): string => `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:acme="http://acme.dev/bpmn/ext/v1"
             targetNamespace="http://acme.dev/bpmn/it">
  <process id="proc-it${marker}">
    <startEvent id="start"/>
    <sequenceFlow id="f0" sourceRef="start" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;

const LAYOUT_JSON = JSON.stringify({
  nodes: [
    { id: 'start', x: 60, y: 80 },
    { id: 'end', x: 300, y: 80 },
  ],
});

const server = setupServer(...handlers);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

afterAll(() => {
  server.close();
});

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useAuthStore.getState().setSession({
    accessToken: 'token-bpm2',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
});

describe('REQ20261122023441 msw 六端点同形（AC-13）', () => {
  it('should_page_definitions_with_wire_string_id_when_list_queried', async () => {
    // ① PageResult 形状 + wire id 字符串 + 列表 bpmnXml 置空
    const page = await workflowApi.list(1, 20);
    expect(page.total).toBeGreaterThanOrEqual(3);
    expect(Array.isArray(page.records)).toBe(true);
    for (const row of page.records) {
      expect(typeof row.id, 'wire id 应为字符串').toBe('string');
      expect(typeof row.version, 'wire version 应为字符串').toBe('string');
      expect(row.bpmnXml ?? null, '列表 bpmnXml 置空').toBeNull();
    }
    // ② keyword 对 key/name 模糊
    const byKeyword = await workflowApi.list(1, 20, { keyword: 'leave' });
    expect(byKeyword.total).toBe(1);
    expect(byKeyword.records[0]?.key).toBe('leave-approval');
    const miss = await workflowApi.list(1, 20, { keyword: `zzz-无命中-${uniqKey('k')}` });
    expect(miss.total).toBe(0);
    // ③ status 精确过滤
    const deployed = await workflowApi.list(1, 20, { status: 'DEPLOYED' });
    expect(deployed.total).toBeGreaterThanOrEqual(1);
    expect(deployed.records.every((row) => row.status === 'DEPLOYED')).toBe(true);
    const disabled = await workflowApi.list(1, 20, { status: 'DISABLED' });
    expect(disabled.records.every((row) => row.status === 'DISABLED')).toBe(true);
  });

  it('should_create_draft_when_key_unique_and_reject_when_key_duplicate', async () => {
    // 正例：POST 创建草稿 → DRAFT v1（wire 字符串）
    const key = uniqKey('create');
    const created = await workflowApi.create({ key, name: '联调演示流程' });
    expect(typeof created.id).toBe('string');
    expect(created.key).toBe(key);
    expect(created.status).toBe('DRAFT');
    expect(created.version).toBe('1');
    // 反例：同 key 再建 → 01-0001 KEY_DUPLICATE（409）
    await expectApiError(
      () => workflowApi.create({ key, name: '重复演示' }),
      WORKFLOW_ERROR_CODES.KEY_DUPLICATE,
    );
  });

  it('should_save_xml_with_layout_then_echo_when_detail_fetched', async () => {
    // AC-6/7/8 载荷面：PUT body 含 bpmnXml+layoutJson 单请求 → GET 全量回显
    const created = await workflowApi.create({ key: uniqKey('saveecho'), name: '保存回显流程' });
    const saved = await workflowApi.saveXml(created.id, { bpmnXml: bpmnXml(), layoutJson: LAYOUT_JSON });
    expect(saved.status).toBe('DRAFT');
    const detail = await workflowApi.get(created.id);
    expect(detail.bpmnXml).toBe(bpmnXml());
    expect(detail.layoutJson).toBe(LAYOUT_JSON);
    // 导入导出载荷（AC-7/8）：XML 含标准 ns + acme: ns 声明
    expect(detail.bpmnXml).toContain('http://www.omg.org/spec/BPMN/20100524/MODEL');
    expect(detail.bpmnXml).toContain('http://acme.dev/bpmn/ext/v1');
  });

  it('should_reject_xml_save_with_codes_when_payload_invalid_or_not_draft', async () => {
    // 01-0002 XML_INVALID（400）：空 bpmnXml / 演示触发 __INVALID__
    const draft = await workflowApi.create({ key: uniqKey('invalid'), name: '非法载荷流程' });
    await expectApiError(
      () => workflowApi.saveXml(draft.id, { bpmnXml: '   ' }),
      WORKFLOW_ERROR_CODES.XML_INVALID,
    );
    await expectApiError(
      () => workflowApi.saveXml(draft.id, { bpmnXml: bpmnXml('__INVALID__') }),
      WORKFLOW_ERROR_CODES.XML_INVALID,
    );
    // 01-0005 DISABLED（409）：仅草稿态可保存（fixture 9002 固定 DEPLOYED，只读不改）
    await expectApiError(
      () => workflowApi.saveXml('9002', { bpmnXml: bpmnXml() }),
      WORKFLOW_ERROR_CODES.DISABLED,
    );
  });

  it('should_return_validation_details_when_deploy_rejected', async () => {
    // 01-0003 明细随 data：交付 mock 的 __VALIDATION_FAILED__ 触发点在保存期（演示口径）
    const draft = await workflowApi.create({ key: uniqKey('valfail'), name: '校验失败流程' });
    const saveErr = await expectApiError(
      () => workflowApi.saveXml(draft.id, { bpmnXml: bpmnXml('__VALIDATION_FAILED__') }),
      WORKFLOW_ERROR_CODES.VALIDATION_FAILED,
    );
    const savedDetails = workflowValidationErrorsOf(saveErr);
    expect(savedDetails.length, '409 明细应逐条随 data 返回').toBeGreaterThanOrEqual(2);
    expect(savedDetails.some((d) => d.nodeId === 'task-1' && d.message !== '')).toBe(true);
    expect(savedDetails.some((d) => d.nodeId === null, '模型级条目 nodeId 可空')).toBe(true);

    // 部署 409 同形信封 → 客户端 ApiError 拆包（交付 handler 的 marker 无法经 API 存入库后再部署，
    // 故用 msw 覆盖桩模拟后端同形响应；页面装配另见 workflowPages 的 ValidationPanel 用例）。
    const detail = [
      { nodeId: 'task-1', field: null, message: '用户任务缺少指派' },
      { nodeId: null, field: null, message: '流程缺少结束事件' },
    ];
    server.use(http.post(WORKFLOW_ENDPOINTS.deploy(draft.id), () => HttpResponse.json({
      success: false, code: WORKFLOW_ERROR_CODES.VALIDATION_FAILED, message: '流程校验未通过', data: detail,
    }, { status: 409 })));
    const err = await expectApiError(() => workflowApi.deploy(draft.id), WORKFLOW_ERROR_CODES.VALIDATION_FAILED);
    expect(err.status).toBe(409);
    expect(workflowValidationErrorsOf(err).map((d) => d.nodeId)).toEqual(['task-1', null]);
  });

  it('should_deploy_and_disable_when_lifecycle_operated', async () => {
    // 正例生命周期：创建 → 存 XML → 部署（DEPLOYED+deployedAt）→ 停用（DISABLED）
    const created = await workflowApi.create({ key: uniqKey('lifecycle'), name: '生命周期流程' });
    await workflowApi.saveXml(created.id, { bpmnXml: bpmnXml() });
    const deployed = await workflowApi.deploy(created.id);
    expect(deployed.status).toBe('DEPLOYED');
    expect(deployed.deployedAt ?? null).not.toBeNull();
    const disabled = await workflowApi.disable(created.id);
    expect(disabled.status).toBe('DISABLED');
    // 反例：DISABLED 再部署 / DRAFT 直接停用 → 01-0005
    await expectApiError(() => workflowApi.deploy(created.id), WORKFLOW_ERROR_CODES.DISABLED);
    const freshDraft = await workflowApi.create({ key: uniqKey('draftdis'), name: '草稿停用流程' });
    await expectApiError(() => workflowApi.disable(freshDraft.id), WORKFLOW_ERROR_CODES.DISABLED);
  });

  it('should_return_not_found_when_definition_unknown', async () => {
    // 01-0004 NOT_FOUND：详情/部署/停用对未知 id
    const ghost = '999999999';
    await expectApiError(() => workflowApi.get(ghost), WORKFLOW_ERROR_CODES.NOT_FOUND);
    await expectApiError(() => workflowApi.deploy(ghost), WORKFLOW_ERROR_CODES.NOT_FOUND);
    await expectApiError(() => workflowApi.disable(ghost), WORKFLOW_ERROR_CODES.NOT_FOUND);
  });
});
