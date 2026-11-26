/** @vitest-environment jsdom */
/**
 * REQ-20261125-230807 AC-13/AC-14/AC-17/AC-18 —— PermissionsPage 仅浏览+两新列、DictsPage 树形字典用例。
 *
 * 覆盖映射（映射总表见 WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-13 权限点页无新建/编辑/删除入口；列表+筛选可用；无 mutation 请求发出（后端端点保留不在前端断言面）。
 *   AC-14 「请求方式」列彩色标签（GET=blue/POST=green/PUT=orange/PATCH=purple/DELETE=red/空=default）；
 *         原 method+path 合并列改仅路径。
 *   AC-17 TREE 类型条目：列表树形展示、新建条目可选父条目（同类型）；违规提交前端提示 PARAM_INVALID 族
 *         转译；FLAT 类型交互零变化（回归锚点：无父条目域、提交不带 parentId）。
 *   AC-18 「新建」与「新建条目」按钮尺寸一致（AC 原文「统一方案随实现」→ 按 antd size class token
 *         一致性断言，两按钮同属 lg/sm/中档之一）。
 *
 * 装配照范本 web/tests/REQ20261124213008-adminFrontendPages.test.tsx；交互防坑照 pitfalls 卡 7/8/9/13/14。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 AdminPermissionView 含 status/httpMethod/httpPath（契约背景段「sys_permission 已有 http_method/
 *       http_path」+ AC-13 状态筛选）；请求方式标签色用 antd 预设名 → class ant-tag-{blue|green|orange|purple|red}。
 *   A-2 AdminDictTypeView 增 dictKind（V35），AdminDictItemView 增 parentId/children（TREE 时嵌套）。
 *   A-3 字典两卡布局：点击类型行加载条目卡；「新建条目」按钮在条目卡内。
 *   A-4 条目表单 label 候选：条目标签/标签/名称 与 条目值/键值/值（宽容填充）。
 *   A-5 实现未落地属预期红（钉死事实⑥）：仅浏览剥离/两新列/dict kind 分支均为本 REQ 新增面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { PermissionsPage } from '../src/pages/PermissionsPage';
import { DictsPage } from '../src/pages/DictsPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 90000 }); // B-组口径（同显式超时族）：同机后端全量测试 CPU 争抢下查询饥饿
                                      // 实测（AC-17 PARAM_INVALID 用例 run1 达 34.5s），预算 45s→90s，断言集不变

// 提交钮定位（B6①）：与同目录用例一致口径（rolesAuth CONFIRM_BUTTON / tenants SUBMIT_BUTTON 的
// /确\s*定|^OK$/i 候选）——页面未装配 ConfigProvider locale 时 antd Modal 缺省 OK 亦可命中
const SUBMIT_BUTTON = /提\s*交|确\s*定|^OK$/i;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

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

const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
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

/** 宽容填充表单域（A-4：label 候选依次尝试）。 */
const fillByLabelCandidates = async (dialog: HTMLElement, candidates: string[], value: string) => {
  for (const label of candidates) {
    const input = dialog.querySelector(`label[title="${label}"], label`) ?
      Array.from(dialog.querySelectorAll('label')).find((el) => new RegExp(`^${label}$`).test(el.textContent ?? '')) : undefined;
    if (!input) continue;
    const item = input.closest('.ant-form-item') as HTMLElement | null;
    const control = item?.querySelector('input.ant-input, textarea, .ant-input') as HTMLInputElement | null;
    if (control) {
      fireEvent.change(control, { target: { value } });
      return true;
    }
  }
  return false;
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

// ————— fixtures —————
const permRow = (id: string, code: string, name: string, httpMethod: string | null, httpPath: string | null) => ({
  id, permissionCode: code, permissionName: name, permissionType: 'API', status: 'ENABLED',
  httpMethod, httpPath, version: '0',
});

const PERMISSION_ROWS = [
  permRow('7001', 'system:user:list', '用户查询', 'GET', '/admin/v1/system/users'),
  permRow('7002', 'system:user:create', '用户新增', 'POST', '/admin/v1/system/users'),
  permRow('7003', 'system:role:update', '角色更新', 'PUT', '/admin/v1/system/roles/{roleId}'),
  permRow('7004', 'system:menu:sync', '菜单同步', 'PATCH', '/admin/v1/system/menus/sort'),
  permRow('7005', 'system:cache:evict', '缓存清理', 'DELETE', '/admin/v1/system/cache'),
  permRow('7006', 'system:job:run', '任务执行', null, null),
];

const permissionsRoutes = () => ({
  '/system/permissions': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: PERMISSION_ROWS, total: PERMISSION_ROWS.length });
    return envelope(null);
  },
});

const DICT_TYPES = [
  { id: '6001', dictCode: 'leave_type', dictName: '请假类型', remark: null, dictKind: 'FLAT', version: '0' },
  { id: '6002', dictCode: 'org_region', dictName: '行政区划', remark: null, dictKind: 'TREE', version: '0' },
];

const TREE_ITEMS = [
  {
    id: '6101', typeId: '6002', itemLabel: '华东', itemValue: 'east', sortOrder: 1, parentId: null, version: '0',
    children: [{ id: '6102', typeId: '6002', itemLabel: '上海', itemValue: 'shanghai', sortOrder: 1, parentId: '6101', version: '0', children: [] }],
  },
  { id: '6103', typeId: '6002', itemLabel: '华北', itemValue: 'north', sortOrder: 2, parentId: null, version: '0', children: [] },
];

const FLAT_ITEMS = [
  { id: '6201', typeId: '6001', itemLabel: '事假', itemValue: 'personal', sortOrder: 1, parentId: null, version: '0' },
  { id: '6202', typeId: '6001', itemLabel: '年假', itemValue: 'annual', sortOrder: 2, parentId: null, version: '0' },
];

const dictRoutes = (opts: { itemCreateFail?: { code: string; message: string } } = {}) => ({
  // 长前缀先匹配：条目端点
  '/system/dict-types/6002/items': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope(TREE_ITEMS);
    if (r.method === 'POST') {
      if (opts.itemCreateFail) return failureEnvelope(opts.itemCreateFail.code, opts.itemCreateFail.message);
      return envelope(TREE_ITEMS[0]);
    }
    return envelope(null);
  },
  '/system/dict-types/6001/items': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope(FLAT_ITEMS);
    if (r.method === 'POST') return envelope(FLAT_ITEMS[0]);
    return envelope(null);
  },
  '/system/dict-types': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: DICT_TYPES, total: DICT_TYPES.length });
    return envelope(null);
  },
});

// ————— AC-13：权限点仅浏览 —————
describe('REQ20261125230807 PermissionsPage 仅浏览（AC-13）', () => {
  it('should_hide_mutation_entries_and_fire_no_mutation_calls_when_permissions_page_rendered', async () => {
    const { fetchMock } = await renderPage(PermissionsPage, ['system:permission:list'], permissionsRoutes());
    await screen.findByText('system:user:list');

    // 无新建/编辑/删除任何入口（确认②：删操作入口收口）
    expect(screen.queryByText(/新\s*建/), '不应有新建入口').toBeNull();
    expect(screen.queryByText(/^编\s*辑$/), '不应有编辑入口').toBeNull();
    expect(screen.queryByText(/^删\s*除$/), '不应有删除入口').toBeNull();

    // 列表 + 状态/方法筛选可用：列表行渲染齐全，筛选控件保留
    for (const code of ['system:user:create', 'system:role:update', 'system:menu:sync']) {
      expect(await screen.findByText(code)).toBeTruthy();
    }
    expect(document.querySelectorAll('.ant-select, .ant-table-tbody').length).toBeGreaterThan(0);

    // 全程无 mutation 请求发出（mock mutation handler 移除的前端面：入口已删，无从发起）
    expect(callsOf(fetchMock, 'POST', '/admin/v1/system/permissions')).toHaveLength(0);
    expect(callsOf(fetchMock, 'PUT', '/admin/v1/system/permissions')).toHaveLength(0);
    expect(callsOf(fetchMock, 'DELETE', '/admin/v1/system/permissions')).toHaveLength(0);
  });
});

// ————— AC-14：请求方式彩色标签列 + 合并列改仅路径 —————
describe('REQ20261125230807 PermissionsPage 请求方式列（AC-14）', () => {
  it('should_render_method_color_tags_and_path_only_cell_when_permission_list_rendered', async () => {
    await renderPage(PermissionsPage, ['system:permission:list'], permissionsRoutes());
    await screen.findByText('system:user:list');

    // B-组定位修复（AC-14 归因=用例错）：行内首个 .ant-tag 是状态列标签（success），
    // 请求方式标签必须按方法文本在行内定位——断言强度不变（仍断言该标签的预设色 class）
    const expectTagColor = async (anchor: string, methodText: string, colorClass: string) => {
      const row = screen.getByText(anchor).closest('tr') as HTMLElement;
      const tag = Array.from(row.querySelectorAll('.ant-tag'))
        .find((el) => (el.textContent ?? '').trim() === methodText) as HTMLElement | undefined;
      expect(tag, `${anchor} 行应渲染「${methodText}」请求方式标签`).toBeTruthy();
      expect(tag!.className, `${anchor} 标签应为 ${colorClass}`).toContain(colorClass);
    };
    await expectTagColor('system:user:list', 'GET', 'ant-tag-blue');     // GET=blue
    await expectTagColor('system:user:create', 'POST', 'ant-tag-green'); // POST=green
    await expectTagColor('system:role:update', 'PUT', 'ant-tag-orange'); // PUT=orange
    await expectTagColor('system:menu:sync', 'PATCH', 'ant-tag-purple'); // PATCH=purple
    await expectTagColor('system:cache:evict', 'DELETE', 'ant-tag-red'); // DELETE=red

    // 空 method → default（无彩色预设 class；按 noMethod 文案「—」定位请求方式列标签）
    const emptyRow = screen.getByText('system:job:run').closest('tr') as HTMLElement;
    const emptyTag = Array.from(emptyRow.querySelectorAll('.ant-tag'))
      .find((el) => (el.textContent ?? '').trim() === '—') as HTMLElement | undefined;
    expect(emptyTag, '空 method 行应有 default 标签').toBeTruthy();
    expect(emptyTag!.className).not.toMatch(/ant-tag-(blue|green|orange|purple|red|success|error|warning|processing)/);

    // 原 method+path 合并列改为仅路径：路径单元格不再携带方法字样
    //（fixture 两行同路径 → getAllByText 取首个，断言语义不变：该单元格仅路径文本）
    const pathCell = screen.getAllByText('/admin/v1/system/users')[0].closest('td') as HTMLElement | null;
    expect(pathCell, '路径单元格应存在').toBeTruthy();
    expect(pathCell!.textContent ?? '', '路径单元格不应再带 GET 字样（仅路径）').not.toMatch(/\bGET\b/);
  });
});

// ————— AC-17：字典树形（TREE 分支 + FLAT 回归锚点） —————
describe('REQ20261125230807 DictsPage 树形字典（AC-17）', () => {
  it('should_show_parent_select_and_tree_items_when_tree_kind_type_selected', async () => {
    const { fetchMock } = await renderPage(DictsPage, ['system:dict:list', 'system:dict:create'], dictRoutes());
    fireEvent.click(await screen.findByText('行政区划'));
    await screen.findByText('华东');

    // 条目列表树形展示：父行有展开图标，展开后子条目可见
    const parentRow = await waitFor(() => {
      const tr = screen.getByText('华东').closest('tr') as HTMLElement;
      expect(tr.querySelector('.ant-table-row-expand-icon'), 'TREE 条目应树形展示（父行有展开图标）').toBeTruthy();
      return tr;
    });
    const expander = parentRow.querySelector('.ant-table-row-expand-icon') as HTMLElement;
    if (expander.getAttribute('aria-expanded') !== 'true') fireEvent.click(expander);
    expect(await screen.findByText('上海')).toBeTruthy();

    // 新建条目可选父条目（同类型条目候选）→ 提交 parentId 字符串原样
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建\s*条\s*目/ }));
    const dialog = await screen.findByRole('dialog');
    const parentLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /父\s*条\s*目|上级条目/.test(el.textContent ?? ''));
    expect(parentLabels.length, 'TREE 类型新建条目应有父条目域').toBeGreaterThanOrEqual(1);
    const parentItem = parentLabels[0].closest('.ant-form-item') as HTMLElement;
    fireEvent.mouseDown(parentItem.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '父条目下拉应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(dropdown).getByText('华东'));

    expect(await fillByLabelCandidates(dialog, ['条目标签', '标签', '名称'], '杭州'), '条目标签域应可填充').toBe(true);
    expect(await fillByLabelCandidates(dialog, ['条目值', '键值', '值'], 'hangzhou'), '条目值域应可填充').toBe(true);
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST', '/admin/v1/system/dict-types/6002/items');
      expect(posts).toHaveLength(1);
      const body = parseBody(posts[0]?.body) ?? {};
      expect(body.parentId, '选父提交应为父条目字符串原样').toBe('6101');
    });
  });

  it('should_surface_param_invalid_feedback_when_tree_parent_rule_violated', async () => {
    await renderPage(DictsPage, ['system:dict:list', 'system:dict:create'],
      dictRoutes({ itemCreateFail: { code: 'PARAM_INVALID', message: '父条目不存在或不属于同字典类型' } }));
    fireEvent.click(await screen.findByText('行政区划'));
    await screen.findByText('华东');

    fireEvent.click(await screen.findByRole('button', { name: /新\s*建\s*条\s*目/ }));
    const dialog = await screen.findByRole('dialog');
    const parentLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /父\s*条\s*目|上级条目/.test(el.textContent ?? ''));
    const parentItem = parentLabels[0].closest('.ant-form-item') as HTMLElement;
    fireEvent.mouseDown(parentItem.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(dropdown).getByText('华北'));
    await fillByLabelCandidates(dialog, ['条目标签', '标签', '名称'], '石家庄');
    await fillByLabelCandidates(dialog, ['条目值', '键值', '值'], 'shijiazhuang');
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));

    // 违规 → PARAM_INVALID 族既有码转译提示（反馈载体存在性断言，pitfall 13/14）
    await waitFor(() => {
      const feedback = document.querySelector('.ant-message-notice-content, .ant-form-item-explain-error, .ant-alert-error, .ant-alert-warning');
      expect(feedback?.textContent ?? '', '选父违规应出现可读反馈').toMatch(/父条目|参数|PARAM_INVALID|无效/);
    });
  });

  it('should_keep_flat_interaction_unchanged_when_flat_kind_type_selected', async () => {
    const { fetchMock } = await renderPage(DictsPage, ['system:dict:list', 'system:dict:create'], dictRoutes());
    fireEvent.click(await screen.findByText('请假类型'));
    await screen.findByText('事假');

    // FLAT 回归锚点：条目平铺（无树形展开图标）、新建条目无父条目域
    const row = screen.getByText('事假').closest('tr') as HTMLElement;
    const icon = row.querySelector('.ant-table-row-expand-icon') as HTMLElement | null;
    expect(icon === null || icon.className.includes('ant-table-row-expand-icon-spaced'), 'FLAT 条目不应树形展示').toBe(true);

    fireEvent.click(await screen.findByRole('button', { name: /新\s*建\s*条\s*目/ }));
    const dialog = await screen.findByRole('dialog');
    const parentLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /父\s*条\s*目|上级条目/.test(el.textContent ?? ''));
    expect(parentLabels.length, 'FLAT 类型新建条目不应有父条目域').toBe(0);

    await fillByLabelCandidates(dialog, ['条目标签', '标签', '名称'], '病假');
    await fillByLabelCandidates(dialog, ['条目值', '键值', '值'], 'sick');
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST', '/admin/v1/system/dict-types/6001/items');
      expect(posts).toHaveLength(1);
      const body = parseBody(posts[0]?.body) ?? {};
      // FLAT 类型提交 parentId → PARAM_INVALID（后端）；前端不发送 parentId
      expect(body.parentId ?? null, 'FLAT 提交不应携带 parentId').toBeNull();
    });
  });
});

// ————— AC-18：两卡新建按钮尺寸一致 —————
describe('REQ20261125230807 DictsPage 新建按钮尺寸（AC-18）', () => {
  it('should_render_new_type_and_new_item_buttons_with_equal_size_when_dicts_page_rendered', async () => {
    // B6②：fixture 对齐真实超管权限面——仅授 list 时「新建」钮被 Auth fail-closed 隐藏（用例权限假设缺口）
    await renderPage(DictsPage, ['system:dict:list', 'system:dict:create'], dictRoutes());
    fireEvent.click(await screen.findByText('请假类型'));
    await screen.findByText('事假');

    const findButtonByText = async (text: RegExp) => {
      const buttons = await screen.findAllByRole('button');
      const hit = buttons.filter((el) => text.test((el.textContent ?? '').replace(/\s+/g, '')));
      expect(hit.length, `应存在按钮 ${text}`).toBeGreaterThanOrEqual(1);
      return hit[0];
    };
    const newTypeBtn = await findButtonByText(/新建$/);
    const newItemBtn = await findButtonByText(/新建条目$/);

    // A-5 口径：统一方案随实现 → 按 antd size class token 一致性断言（lg/sm/中档同档）
    const sizeToken = (btn: HTMLElement) =>
      btn.className.includes('ant-btn-lg') ? 'lg' : btn.className.includes('ant-btn-sm') ? 'sm' : 'middle';
    expect(sizeToken(newTypeBtn), '两卡新建按钮尺寸应一致（AC-18）').toBe(sizeToken(newItemBtn));
  });
});
