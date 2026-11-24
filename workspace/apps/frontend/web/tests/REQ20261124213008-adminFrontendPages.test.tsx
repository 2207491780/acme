/** @vitest-environment jsdom */
/**
 * REQ-20261124-213008 前端补齐管理面功能——页面交互层契约用例（AC-1~AC-14 页面半句 + AC-15 自然覆盖）。
 *
 * 覆盖映射（api 半句见 packages/core/tests/REQ20261124213008-adminLeaveApi.test.ts；映射总表见
 * WT/.ai-tmp/REQ-20261124-213008-tester-mapping.md）：
 *   AC-1/2/3   UsersPage 重置密码（行操作「重置密码」auth:credential:manage 显隐 → Modal + Input.Password
 *              标签「新密码」→ 成功 message.success('密码已重置，该用户将被强制下线') 关弹窗；策略违反转译+输入保留）
 *   AC-4/5     FilesPage 行操作「详情」→ Drawer + Descriptions 展示 FileView（FILE_NOT_FOUND 转译、不展示空壳数据）
 *   AC-6/7/8   PositionsPage「状态」列 Switch（checked=ENABLED；无 system:position:update 禁用；成功刷新列表；
 *              非 0 code 回滚开关态并转译）
 *   AC-9/10    RolesPage Segmented「平铺/树形」默认平铺（树形调 roles/tree 渲染层级，子行缩进+关键字段）
 *   AC-11      WorkflowPage 定义行「详情」→ Drawer 展示 ProcessDefinitionView 关键字段（不复用 XML 抽屉数据面）
 *   AC-12/13/14 TodoCenterPage 头部「发起请假」→ Modal（Select 请假类型：事假/年假/病假；TextArea 事由；
 *              RangePicker 起止时间）→ 成功关 Modal 并以返回 id 调 GET 详情打开详情 Drawer（状态中文映射
 *              APPLYING/APPROVING/FILED/DONE/REJECTED→已建单/审批中/已备案/已完成/已驳回 + 流程实例号可空展示）；
 *              必填缺失/结束早于开始前端拦截不发请求；后端非 0 code 转译提示
 *   AC-15      mock 同形（委派单：不单独建文件）：全部用例按统一响应体 Result 信封 + 业务错误 code≠0（HTTP 200）
 *              承载，与后端契约同形；不新增菜单项（REQ-20261122-233425 确认⑦口径）无断言面（G：无菜单变更）。
 *
 * 装配照钉死范本 web/tests/REQ20261117061017-loggingJobs.test.tsx：fetch stubGlobal 弱匹配路由（callsOf/lastQuery
 * 照抄）+ useMeStore 权限播种（权限点字符串数组）+ <AntApp> 包裹 + jsdom 环境头 + testTimeout ≥15000ms；
 * 关闭态断言一律数据驱动（usersPage round2 裁决⑪：jsdom25+cssstyle4.6 下 antd 关闭动效永不结束，勿断言
 * dialog/drawer 从 DOM 卸载）。WorkflowPage/TodoCenterPage 需 Router（页面 useNavigate）→ MemoryRouter 包裹
 * （REQ20261122102730 先例）；X6 链路隔离照抄该先例的 canvasFactory 桩。
 * 交互防坑照 pitfalls 卡：下拉打 .ant-select-* 结构节点并在浮层容器内选中文案（7/8）、行内操作行作用域限定、
 * 负例消息 contains/正则（13）、文案宽容匹配（14）；RangePicker 输入照 REQ20261117025200 的 antd 官方 jsdom
 * 口径（focus→change→Enter）。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 权限播种=权限点字符串数组（范本口径）；页面直渲染不经过路由层 PermissionGate，分控断言只锚定
 *       页面内行为（行内入口显隐/开关禁用）。
 *   A-2 leaveType wire 编码未契约化（钉死事实仅钉 Select 选项=事假/年假/病假）→ POST body.leaveType 断言
 *       非空字符串；选项交互按显示名断言。
 *   A-3 FileView wire 形状=REQ-20261114-071607 §API（id/originalName/size/contentType/url/bizType/bizId/
 *       createdAt，无「上传人」字段）→ AC-4 抽屉全字段断言按既有 FileView 字段值；「上传人」不在 wire 形状内
 *       不做显示断言（红则 TL 裁决）。
 *   A-4 RolesPage 树形渲染=antd rc-table 树形数据（自验 round1 实证：行首 .ant-table-row-expand-icon +
 *       ant-table-row-indent）→ 子行缩进以 .ant-table-row-indent 结构节点断言；子行默认折叠 → 先等树形表
 *       真正挂载（super_admin 行出现展开钮）再点击展开（避免命中切换瞬间仍在场的旧平铺表行）。
 *   A-6 RangePicker 输入串 'YYYY-MM-DD HH:mm:ss'（startTime/endTime 为 Instant，showTime 概率高）；
 *       弹窗提交按钮文案未钉死 → 「提交/确定」双口径宽容正则（/提交|确定|^OK$/i）。
 *   A-7 密码策略违反的转译文案未钉死（AC-2 只钉「转译为可读提示」）→ 反馈存在性断言（message/form error/
 *       alert 任一载体出现）+ 输入保留 + 无成功提示；文案收红则 TL 裁决。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { UsersPage } from '../src/pages/UsersPage';
import { FilesPage } from '../src/pages/FilesPage';
import { PositionsPage } from '../src/pages/PositionsPage';
import { RolesPage } from '../src/pages/RolesPage';
import { WorkflowPage } from '../src/pages/WorkflowPage';
import { TodoCenterPage } from '../src/pages/TodoCenterPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

// X6 链路隔离（REQ20261122102730 先例照抄）：WorkflowPage/TodoCenterPage→canvasFactory 静态拉入 @antv/x6，
// x6-common polyfill 在 jsdom 收集期抛 Element is not defined；画布装配以桩替换
// （readOnly 流程图高亮/边标红=X6 浏览器实测项，本文件只断言 Drawer 各块装配面）。
vi.mock('../src/pages/workflow/canvasFactory', () => ({
  createBpmnCanvas: vi.fn(() => {
    throw new Error('jsdom 不渲染画布（X6 浏览器实测项）');
  }),
  renderReadOnlyBpmn: vi.fn(),
  applyCanvasTheme: vi.fn(),
}));

// ————— 行为常量（范本口径：两字按钮 autoInsertSpace 兼容） —————
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const SUBMIT_BUTTON = /提\s*交|确\s*定|^OK$/i; // A-6：弹窗提交按钮文案未钉死，双口径宽容
const DETAIL_BUTTON = /详\s*情/;
const RESET_BUTTON = /重\s*置\s*密\s*码/;
const LEAVE_BUTTON = /发\s*起\s*请\s*假/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

// ————— fetch 边界 mock（范本 helpers 照抄 loggingJobs） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> | null => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return null;
  }
};

// ————— 页面级 fetch mock（按 pathPart 弱匹配路由；entries 顺序即匹配优先级） —————
const setupApi = (routes: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    for (const [part, handler] of Object.entries(routes)) {
      if (r.url.pathname.includes(part)) {
        return handler(r);
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

// ————— 装配（范本口径照抄 loggingJobs） —————
const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    // min-width 断点全命中=桌面侧边导航（范本 nav.test 口径）
    matches: query.includes('min-width'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const seedMe = async (permissions: string[]): Promise<void> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: {
      id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
      status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
    },
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
};

const renderPage = async (Component: ComponentType, permissions: string[], routes?: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  await seedMe(permissions);
  const api = routes ? setupApi(routes) : setupApi({});
  render(<AntApp><Component /></AntApp>);
  return api;
};

/** WorkflowPage/TodoCenterPage 需 Router（页面 useNavigate）——REQ20261122102730 先例口径。 */
const renderRouterPage = async (Component: ComponentType, permissions: string[], routes?: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  await seedMe(permissions);
  const api = routes ? setupApi(routes) : setupApi({});
  render(<AntApp><MemoryRouter><Component /></MemoryRouter></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— fixtures（契约 §API / 既往契约用例 wire 形状；id wire string） —————
const USER_ROWS = [
  { id: '2001', username: 'alice', nickname: '爱丽丝', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
  { id: '2002', username: 'bob', nickname: '鲍勃', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
];

const FILE_ROWS = [
  { id: '9001', originalName: '季度报表.xlsx', size: 1024, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', url: 'http://files.localhost/acme/files/2026/11/24/9001', bizType: 'demo', bizId: '41', createdAt: '2026-11-14T08:30:00Z' },
  { id: '9002', originalName: '合同扫描件.pdf', size: 1024, contentType: 'application/pdf', url: 'http://files.localhost/acme/files/2026/11/24/9002', bizType: 'demo', bizId: '42', createdAt: '2026-11-14T08:30:00Z' },
];

const POSITION_ROWS = [
  { id: '5001', positionCode: 'frontend_eng', positionName: '前端工程师', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0' },
  { id: '5002', positionCode: 'product_mgr', positionName: '产品经理', status: 'ENABLED', createdAt: '2026-01-02T00:00:00Z', version: '0' },
];

const ROLE_FLAT_ROWS = [
  { id: '3101', parentId: '0', roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: '', createdAt: '2026-01-01T00:00:00Z', children: [], version: '0' },
  { id: '3102', parentId: '0', roleCode: 'ops', roleName: '运维员', status: 'ENABLED', builtIn: false, remark: '', createdAt: '2026-01-02T00:00:00Z', children: [], version: '0' },
];

const ROLE_TREE = [
  {
    id: '3101', parentId: null, roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED',
    builtIn: true, remark: '', createdAt: '2026-01-01T00:00:00Z', version: '0',
    children: [{
      id: '3103', parentId: '3101', roleCode: 'sec_officer', roleName: '安全员', status: 'ENABLED',
      builtIn: false, remark: '', createdAt: '2026-01-03T00:00:00Z', version: '0', children: [],
    }],
  },
  {
    id: '3102', parentId: null, roleCode: 'ops', roleName: '运维员', status: 'ENABLED',
    builtIn: false, remark: '', createdAt: '2026-01-02T00:00:00Z', version: '0', children: [],
  },
];

const DEFS = [
  { id: '9001', key: 'leave-approval', name: '请假审批', version: '3', status: 'DRAFT', bpmnXml: null, layoutJson: null, createdAt: '2026-11-21T02:00:00Z', deployedAt: null },
  { id: '9002', key: 'expense-claim', name: '报销申请', version: '1', status: 'DEPLOYED', bpmnXml: null, layoutJson: null, createdAt: '2026-11-20T08:00:00Z', deployedAt: '2026-11-20T09:30:00Z' },
];

const TASK_ROW = {
  id: '9401', instanceId: '9301', nodeId: 'task-1', nodeName: '主管审批', assignee: null,
  status: 'CREATED', claimTime: null, completeTime: null, createTime: '2026-11-22T01:30:05Z',
  definitionKey: 'expense-claim', definitionName: '报销申请', businessKey: 'EXP-20261122-001', initiator: 'admin',
};

const LEAVE_VIEW = {
  id: '8801', userName: 'admin', leaveType: '事假', reason: '家里有事',
  startTime: '2026-11-25T01:00:00Z', endTime: '2026-11-26T03:00:00Z',
  status: 'APPLYING', instanceId: null as string | null, businessKey: 'LEAVE-mock-8801',
};

const PERMS_TODO = ['workflow:task:list', 'workflow:task:operate'];

// ————— AC-1/AC-2/AC-3：UsersPage 重置密码（页面半句） —————
describe('REQ20261124213008 UsersPage 重置密码（AC-1/AC-2/AC-3）', () => {
  const usersRoutes = (opts: { putFail?: { code: string; message: string } } = {}) => ({
    // 长前缀先匹配：PUT /admin/v1/auth/credentials/users/{userId}/password
    '/auth/credentials/users': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'PUT') {
        if (opts.putFail) return failureEnvelope(opts.putFail.code, opts.putFail.message);
        return envelope(null);
      }
      return envelope(null);
    },
    '/system/users': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') return envelope({ records: USER_ROWS, total: USER_ROWS.length });
      return envelope(null);
    },
  });

  it('should_submit_new_password_and_toast_when_reset_modal_confirmed', async () => {
    const { fetchMock } = await renderPage(UsersPage, ['system:user:list', 'auth:credential:manage'], usersRoutes());
    const rowEl = await findRowByText('alice');
    fireEvent.click(await within(rowEl).findByText(RESET_BUTTON));

    const dialog = await screen.findByRole('dialog');
    const pwdInput = within(dialog).getByLabelText('新密码') as HTMLInputElement;
    fireEvent.change(pwdInput, { target: { value: 'Str0ng!Pass@2026' } });
    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));

    // 发出 PUT /admin/v1/auth/credentials/users/{userId}/password（body {password}，路径锚定行内 userId）
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', '/admin/v1/auth/credentials/users/2001/password');
      expect(puts).toHaveLength(1);
      expect(puts[0]?.url.pathname).toBe('/admin/v1/auth/credentials/users/2001/password');
      expect(String(puts[0]?.body ?? '')).toContain('"password"');
      expect(String(puts[0]?.body ?? '')).toContain('Str0ng!Pass@2026');
    });
    // 成功提示逐字锚定（钉死范式 A）；关弹窗=数据驱动口径（提示出现，勿断言 DOM 卸载）
    await waitFor(() => {
      expect(document.body.textContent ?? '').toContain('密码已重置，该用户将被强制下线');
    });
  });

  it('should_keep_modal_open_with_input_when_policy_violation_returned', async () => {
    await renderPage(UsersPage, ['system:user:list', 'auth:credential:manage'],
      usersRoutes({ putFail: { code: 'PASSWORD_POLICY_VIOLATED', message: '密码强度不符合策略' } }));
    const rowEl = await findRowByText('alice');
    fireEvent.click(await within(rowEl).findByText(RESET_BUTTON));

    await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'Str0ng!Pass@2026' } });
    fireEvent.click(await within(screen.getByRole('dialog')).findByRole('button', { name: CONFIRM_BUTTON }));

    // 后端 PASSWORD_POLICY_VIOLATED 被转译为可读提示（A-7：文案未钉死 → 反馈载体存在性断言）
    await waitFor(() => {
      const feedback = document.querySelector(
        '.ant-message-notice-content, .ant-form-item-explain-error, .ant-alert-error, .ant-alert-warning',
      );
      expect(feedback?.textContent ?? '', '策略违反反馈应出现').toBeTruthy();
    });
    // 弹窗保持打开、输入保留（数据驱动：关闭态 DOM 不卸载，断言输入值仍在且无成功提示）
    expect((screen.getByLabelText('新密码') as HTMLInputElement).value).toBe('Str0ng!Pass@2026');
    expect(document.body.textContent ?? '').not.toContain('密码已重置，该用户将被强制下线');
  });

  it('should_hide_reset_entry_when_credential_manage_permission_missing', async () => {
    await renderPage(UsersPage, ['system:user:list'], usersRoutes());
    const rowEl = await findRowByText('alice');
    // 无 auth:credential:manage → 行内不出现「重置密码」入口（PermissionGate 显隐口径）
    expect(within(rowEl).queryByText(RESET_BUTTON)).toBeNull();
  });
});

// ————— AC-4/AC-5：FilesPage 详情抽屉（页面半句） —————
describe('REQ20261124213008 FilesPage 文件详情（AC-4/AC-5）', () => {
  const filesRoutes = (opts: { detailFail?: { code: string; message: string } } = {}) => ({
    // 长前缀先匹配：GET /admin/v1/infra/files/{fileId}
    '/infra/files/9002': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') {
        if (opts.detailFail) return failureEnvelope(opts.detailFail.code, opts.detailFail.message);
        return envelope(FILE_ROWS[1]);
      }
      return envelope(null);
    },
    '/infra/files': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') return envelope({ records: FILE_ROWS, total: FILE_ROWS.length });
      return envelope(null);
    },
  });

  it('should_fetch_file_detail_and_render_drawer_fields_when_detail_clicked', async () => {
    const { fetchMock } = await renderPage(FilesPage, ['infra:file:list'], filesRoutes());
    const rowEl = await findRowByText('合同扫描件.pdf');
    fireEvent.click(await within(rowEl).findByText(DETAIL_BUTTON));

    await waitFor(() => {
      const gets = callsOf(fetchMock, 'GET', '/admin/v1/infra/files/9002');
      expect(gets).toHaveLength(1);
      expect(gets[0]?.url.pathname).toBe('/admin/v1/infra/files/9002');
    });
    // Drawer 展示 FileView 全字段（A-3：按 071607 FileView wire 字段值断言；「上传人」不在 wire 形状内不断言）
    const drawer = await waitFor(() => {
      const el = document.querySelector('.ant-drawer-content');
      expect(el, '详情 Drawer 应打开').toBeTruthy();
      return el as HTMLElement;
    });
    const text = drawer.textContent ?? '';
    expect(text).toContain('合同扫描件.pdf');      // 原名
    expect(text).toContain('application/pdf');    // 类型
    expect(text).toMatch(/1\s*KB|1024/);          // 大小
    expect(text).toContain('demo');               // 业务域 bizType
    expect(text).toContain('42');                 // 业务域 bizId
    expect(text).toContain('2026-11-14');         // 时间
  });

  it('should_show_translated_hint_without_drawer_data_when_file_not_found', async () => {
    const { fetchMock } = await renderPage(FilesPage, ['infra:file:list'],
      filesRoutes({ detailFail: { code: 'FILE_NOT_FOUND', message: '文件不存在或已删除' } }));
    const rowEl = await findRowByText('合同扫描件.pdf');
    fireEvent.click(await within(rowEl).findByText(DETAIL_BUTTON));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/infra/files/9002')).toHaveLength(1);
    });
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/FILE_NOT_FOUND|文件不存在/);
    });
    // 不展示空壳抽屉数据（数据驱动：抽屉开/不开两种实现皆绿——若打开则内容不含 FileView 字段值）
    const drawer = document.querySelector('.ant-drawer-content');
    if (drawer) expect(drawer.textContent ?? '').not.toContain('application/pdf');
  });
});

// ————— AC-6/AC-7/AC-8：PositionsPage 启停开关（页面半句） —————
describe('REQ20261124213008 PositionsPage 启停开关（AC-6/AC-7/AC-8）', () => {
  const positionsRoutes = (opts: { putFail?: { code: string; message: string } } = {}) => {
    const state = { rows: POSITION_ROWS.map((row) => ({ ...row })) };
    const routes = {
      // 长前缀先匹配：PUT /admin/v1/system/positions/{positionId}/status
      '/system/positions/': (r: ReturnType<typeof asRequest>) => {
        if (r.method === 'PUT' && r.url.pathname.endsWith('/status')) {
          if (opts.putFail) return failureEnvelope(opts.putFail.code, opts.putFail.message);
          const id = r.url.pathname.match(/\/system\/positions\/([^/]+)\/status$/)?.[1] ?? '';
          const body = parseBody(r.body);
          const status = String(body?.status ?? '');
          state.rows = state.rows.map((row) => (row.id === id ? { ...row, status } : row));
          return envelope(null);
        }
        return envelope(null);
      },
      '/system/positions': (r: ReturnType<typeof asRequest>) => {
        if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
        return envelope(null);
      },
    };
    return { state, routes };
  };

  it('should_put_status_and_refresh_list_when_position_switch_toggled', async () => {
    const { routes } = positionsRoutes();
    const { fetchMock } = await renderPage(PositionsPage, ['system:position:list', 'system:position:update'], routes);
    const rowEl = await findRowByText('产品经理');
    const sw = await within(rowEl).findByRole('switch');
    expect(sw.getAttribute('aria-checked')).toBe('true');

    fireEvent.click(sw);

    // 发出 PUT .../positions/{id}/status（body status=DISABLED，checked=ENABLED 取反）
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', '/admin/v1/system/positions/5002/status');
      expect(puts).toHaveLength(1);
      expect(puts[0]?.url.pathname).toBe('/admin/v1/system/positions/5002/status');
      expect(String(puts[0]?.body ?? '')).toContain('DISABLED');
    });
    // 成功后刷新列表（fixture 已迁移状态 → 行开关回到 DISABLED，数据驱动）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/system/positions').length).toBeGreaterThanOrEqual(2);
    });
    await waitFor(() => {
      const refreshed = screen.getByText('产品经理').closest('tr') as HTMLElement;
      expect(refreshed.querySelector('[role="switch"]')?.getAttribute('aria-checked')).toBe('false');
    });
  });

  it('should_disable_position_switch_when_update_permission_missing', async () => {
    await renderPage(PositionsPage, ['system:position:list'], positionsRoutes().routes);
    const rowEl = await findRowByText('产品经理');
    // 无 system:position:update → 开关禁用（钉死范式 C：禁用；勿对裸 role 直接操作前的行为断言）
    const sw = await within(rowEl).findByRole('switch');
    expect((sw as HTMLButtonElement).disabled).toBe(true);
  });

  it('should_rollback_switch_and_show_error_when_position_status_rejected', async () => {
    const { routes } = positionsRoutes({ putFail: { code: 'CONCURRENT_MODIFICATION', message: '数据已被修改，请刷新后重试' } });
    const { fetchMock } = await renderPage(PositionsPage, ['system:position:list', 'system:position:update'], routes);
    const rowEl = await findRowByText('产品经理');
    fireEvent.click(await within(rowEl).findByRole('switch'));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', '/admin/v1/system/positions/5002/status')).toHaveLength(1);
    });
    // 非 0 code → 转译错误提示（未知码回退后端文案——fileTransfer 先例）+ 开关回滚为切换前值
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/数据已被修改|CONCURRENT_MODIFICATION/);
    });
    await waitFor(() => {
      const row = screen.getByText('产品经理').closest('tr') as HTMLElement;
      expect(row.querySelector('[role="switch"]')?.getAttribute('aria-checked')).toBe('true');
    });
  });
});

// ————— AC-9/AC-10：RolesPage 树形视图（页面半句） —————
describe('REQ20261124213008 RolesPage 树形视图（AC-9/AC-10）', () => {
  const rolesRoutes = () => ({
    // 长前缀先匹配：GET /admin/v1/system/roles/tree
    '/system/roles/tree': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') return envelope(ROLE_TREE);
      return envelope(null);
    },
    '/system/roles': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') return envelope({ records: ROLE_FLAT_ROWS, total: ROLE_FLAT_ROWS.length });
      return envelope(null);
    },
  });

  /** 等树形表真正挂载（super_admin 行出现行首展开钮）再展开——避免命中切换瞬间的旧平铺表行（A-4 实证口径）。 */
  const expandTreeParent = async (): Promise<HTMLElement> => {
    const row = await waitFor(() => {
      const tr = (screen.getByText('super_admin').closest('tr') as HTMLElement);
      expect(tr.querySelector('.ant-table-row-expand-icon'), '树形表行首展开钮应出现').toBeTruthy();
      return tr;
    });
    const expander = row.querySelector('.ant-table-row-expand-icon') as HTMLElement;
    if (expander.getAttribute('aria-expanded') !== 'true') fireEvent.click(expander);
    return row;
  };

  it('should_fetch_role_tree_when_tree_view_selected_and_keep_flat_default', async () => {
    const { fetchMock } = await renderPage(RolesPage, ['system:role:list'], rolesRoutes());
    // 默认平铺（现状不回归）：平铺行渲染、树端点未调用
    await findRowByText('super_admin');
    expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree')).toHaveLength(0);

    fireEvent.click(screen.getAllByText('树形')[0]);

    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree')).toHaveLength(1);
    });
    // 按 children 递归渲染层级（等树形表挂载后展开父行——A-4）
    await expandTreeParent();
    expect(await screen.findByText('安全员')).toBeTruthy();
  });

  it('should_render_child_role_indented_with_key_columns_when_tree_loaded', async () => {
    await renderPage(RolesPage, ['system:role:list'], rolesRoutes());
    await findRowByText('super_admin');
    fireEvent.click(screen.getAllByText('树形')[0]);
    const parentRow = await expandTreeParent();

    // 子节点缩进可见 + 既有列关键字段（角色名/编码/状态——状态文案宽容匹配，pitfall 14）
    const childCell = await screen.findByText('安全员');
    const childRow = childCell.closest('tr') as HTMLElement;
    expect(childRow.querySelector('.ant-table-row-indent')).toBeTruthy();
    expect(childRow.textContent ?? '').toContain('sec_officer');
    expect(childRow.textContent ?? '').toMatch(/ENABLED|启\s*用|正\s*常/);
    expect(parentRow.textContent ?? '').toContain('super_admin');
  });
});

// ————— AC-11：WorkflowPage 定义详情抽屉（页面半句） —————
describe('REQ20261124213008 WorkflowPage 定义详情（AC-11）', () => {
  const workflowRoutes = () => ({
    // 长前缀先匹配：GET /admin/v1/workflow/process-definitions/{definitionId}
    '/process-definitions/9002': (r: ReturnType<typeof asRequest>) => (r.method === 'GET' ? envelope(DEFS[1]) : envelope(null)),
    '/workflow/process-definitions': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') return envelope({ records: DEFS, total: DEFS.length });
      return envelope(null);
    },
  });

  it('should_fetch_definition_detail_and_render_drawer_when_detail_clicked', async () => {
    const { fetchMock } = await renderRouterPage(WorkflowPage, ['workflow:definition:list'], workflowRoutes());
    const rowEl = await findRowByText('expense-claim');
    fireEvent.click(await within(rowEl).findByText(DETAIL_BUTTON));

    await waitFor(() => {
      const gets = callsOf(fetchMock, 'GET', '/admin/v1/workflow/process-definitions/9002');
      expect(gets).toHaveLength(1);
      expect(gets[0]?.url.pathname).toBe('/admin/v1/workflow/process-definitions/9002');
    });
    // Drawer 展示 ProcessDefinitionView 关键字段（版本/状态/部署时间等）——值驱动断言
    const drawer = await waitFor(() => {
      const el = document.querySelector('.ant-drawer-content');
      expect(el, '详情 Drawer 应打开').toBeTruthy();
      return el as HTMLElement;
    });
    const text = drawer.textContent ?? '';
    expect(text).toContain('expense-claim');
    expect(text).toContain('报销申请');
    expect(text).toMatch(/DEPLOYED|已部署/);
    expect(text).toContain('2026-11-20'); // 部署时间
  });
});

// ————— AC-12/AC-13/AC-14：TodoCenterPage 发起请假（页面半句） —————
describe('REQ20261124213008 TodoCenterPage 发起请假（AC-12/AC-13/AC-14）', () => {
  const todoBase = () => ({
    '/workflow/tasks/todo': (r: ReturnType<typeof asRequest>) => (r.method === 'GET' ? envelope({ records: [TASK_ROW], total: 1 }) : envelope(null)),
    '/workflow/tasks/done': (r: ReturnType<typeof asRequest>) => (r.method === 'GET' ? envelope({ records: [], total: 0 }) : envelope(null)),
  });

  const leaveRoutes = (opts: { applyFail?: { code: string; message: string }; detail?: Partial<typeof LEAVE_VIEW> } = {}) => ({
    ...todoBase(),
    // 长前缀先匹配：GET /admin/v1/leave/requests/{id}
    '/admin/v1/leave/requests/8801': (r: ReturnType<typeof asRequest>) => (r.method === 'GET' ? envelope({ ...LEAVE_VIEW, ...opts.detail }) : envelope(null)),
    '/admin/v1/leave/requests': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'POST') {
        if (opts.applyFail) return failureEnvelope(opts.applyFail.code, opts.applyFail.message);
        return envelope(LEAVE_VIEW);
      }
      return envelope(null);
    },
  });

  const openLeaveModal = async (): Promise<HTMLElement> => {
    fireEvent.click(await screen.findByRole('button', { name: LEAVE_BUTTON }));
    return await screen.findByRole('dialog');
  };

  /** 填请假弹窗（pitfall 12：含 await 辅助函数显式返回类型）。 */
  const fillLeaveForm = async (dialog: HTMLElement, times: { start: string; end: string }): Promise<void> => {
    // 请假类型 Select（pitfall 7/8：容器内 .ant-select-selector 展开，浮层容器内按显示名选中）
    const selectors = dialog.querySelectorAll('.ant-select-selector');
    expect(selectors.length, '弹窗应有请假类型下拉').toBeGreaterThanOrEqual(1);
    fireEvent.mouseDown(selectors[0] as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '请假类型下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(dropdown).getByText('事假'));
    // 事由 TextArea（AC-12 范式段 label「事由」）
    fireEvent.change(within(dialog).getByLabelText('事由'), { target: { value: '家里有事' } });
    // 起止时间 RangePicker（antd 官方 jsdom 口径：focus→change→Enter，tenantEnhance 先例）
    const startInput = within(dialog).getByLabelText('起止时间') as HTMLInputElement;
    const pickerRoot = startInput.closest('.ant-picker') as HTMLElement | null;
    fireEvent.focus(startInput);
    fireEvent.change(startInput, { target: { value: times.start } });
    fireEvent.keyDown(startInput, { key: 'Enter', keyCode: 13, which: 13 });
    const endInput = pickerRoot?.querySelectorAll('input')[1] as HTMLInputElement | undefined;
    expect(endInput, 'RangePicker 应有结束输入').toBeTruthy();
    fireEvent.focus(endInput!);
    fireEvent.change(endInput!, { target: { value: times.end } });
    fireEvent.keyDown(endInput!, { key: 'Enter', keyCode: 13, which: 13 });
  };

  it('should_post_leave_with_generated_business_key_and_iso_times_when_apply_submitted', async () => {
    const { fetchMock } = await renderRouterPage(TodoCenterPage, PERMS_TODO, leaveRoutes());
    await screen.findByText('EXP-20261122-001'); // 待办列表装配完成（renderTodoPage 先例口径）

    const dialog = await openLeaveModal();
    await fillLeaveForm(dialog, { start: '2026-11-25 09:00:00', end: '2026-11-26 18:00:00' });
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'POST', '/admin/v1/leave/requests')).toHaveLength(1);
    });
    const post = callsOf(fetchMock, 'POST', '/admin/v1/leave/requests')[0];
    expect(post?.url.pathname).toBe('/admin/v1/leave/requests');
    const body = parseBody(post?.body) ?? {};
    // businessKey=前端生成 LEAVE-{uuid}（契约 §API；生成位置页面/封装皆落在 wire body 上）
    expect(String(body.businessKey)).toMatch(/^LEAVE-[0-9a-fA-F-]{10,}$/);
    // leaveType wire 编码未契约化（A-2）：非空字符串
    expect(typeof body.leaveType).toBe('string');
    expect(String(body.leaveType).length).toBeGreaterThan(0);
    expect(body.reason).toBe('家里有事');
    // startTime/endTime 转 ISO-8601（契约 §API）
    expect(String(body.startTime)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
    expect(String(body.endTime)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
  });

  it('should_open_detail_drawer_with_mapped_status_when_apply_succeeded', async () => {
    const { fetchMock } = await renderRouterPage(TodoCenterPage, PERMS_TODO, leaveRoutes());
    await screen.findByText('EXP-20261122-001');

    const dialog = await openLeaveModal();
    await fillLeaveForm(dialog, { start: '2026-11-25 09:00:00', end: '2026-11-26 18:00:00' });
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));

    // 成功后关 Modal 并以返回 id（8801）调 GET 详情打开详情 Drawer
    await waitFor(() => {
      const gets = callsOf(fetchMock, 'GET', '/admin/v1/leave/requests/8801');
      expect(gets).toHaveLength(1);
      expect(gets[0]?.url.pathname).toBe('/admin/v1/leave/requests/8801');
    });
    const drawer = await waitFor(() => {
      const el = document.querySelector('.ant-drawer-content');
      expect(el, '请假详情 Drawer 应打开').toBeTruthy();
      return el as HTMLElement;
    });
    // 状态中文映射（APPLYING→已建单）+ 流程实例号可空展示（fixture instanceId=null）
    const text = drawer.textContent ?? '';
    expect(text).toContain('已建单');
    expect(text).toContain('LEAVE-mock-8801');
  });

  it('should_render_approving_status_with_instance_id_when_detail_loaded', async () => {
    const { fetchMock } = await renderRouterPage(TodoCenterPage, PERMS_TODO,
      leaveRoutes({ detail: { status: 'APPROVING', instanceId: '9301' } }));
    await screen.findByText('EXP-20261122-001');

    const dialog = await openLeaveModal();
    await fillLeaveForm(dialog, { start: '2026-11-25 09:00:00', end: '2026-11-26 18:00:00' });
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/leave/requests/8801')).toHaveLength(1);
    });
    // 状态中文映射（APPROVING→审批中）+ 流程实例号展示（instanceId=9301）
    const drawer = await waitFor(() => {
      const el = document.querySelector('.ant-drawer-content');
      expect(el, '请假详情 Drawer 应打开').toBeTruthy();
      return el as HTMLElement;
    });
    const text = drawer.textContent ?? '';
    expect(text).toContain('审批中');
    expect(text).toContain('9301');
  });

  it('should_block_request_when_required_missing_or_end_before_start', async () => {
    const { fetchMock } = await renderRouterPage(TodoCenterPage, PERMS_TODO, leaveRoutes());
    await screen.findByText('EXP-20261122-001');

    // ① 必填缺失：不填直接提交 → 前端校验拦截不发请求
    const dialog = await openLeaveModal();
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    expect(callsOf(fetchMock, 'POST', '/admin/v1/leave/requests')).toHaveLength(0);

    // ② 结束早于开始：表单形状完整但 end<start → 前端校验拦截不发请求
    await fillLeaveForm(dialog, { start: '2026-11-26 09:00:00', end: '2026-11-25 09:00:00' });
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    expect(callsOf(fetchMock, 'POST', '/admin/v1/leave/requests')).toHaveLength(0);
  });

  it('should_translate_backend_error_when_leave_apply_rejected', async () => {
    const { fetchMock } = await renderRouterPage(TodoCenterPage, PERMS_TODO,
      leaveRoutes({ applyFail: { code: 'LEAVE_PERIOD_INVALID', message: '结束时间不能早于开始时间' } }));
    await screen.findByText('EXP-20261122-001');

    const dialog = await openLeaveModal();
    await fillLeaveForm(dialog, { start: '2026-11-25 09:00:00', end: '2026-11-26 18:00:00' });
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));

    // 后端校验失败（非 0 code，HTTP 200 业务信封）→ 转译提示
    await waitFor(() => {
      expect(callsOf(fetchMock, 'POST', '/admin/v1/leave/requests')).toHaveLength(1);
    });
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/LEAVE_PERIOD_INVALID|结束时间不能早于开始时间/);
    });
    // 失败不走详情（数据驱动：详情端点未被调用）
    expect(callsOf(fetchMock, 'GET', '/admin/v1/leave/requests/8801')).toHaveLength(0);
  });
});
