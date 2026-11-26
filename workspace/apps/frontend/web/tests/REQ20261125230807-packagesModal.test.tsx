/** @vitest-environment jsdom */
/**
 * REQ-20261125-230807 AC-24/AC-21（套餐弹窗半句）—— TenantPackagesPage 契约用例。
 *
 * 覆盖映射（映射总表见 WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-24  绑定菜单弹窗：菜单树全量展开后，树区域（及能力复选组）在限高容器内滚动，弹窗整体不无限
 *          撑高、footer 按钮始终可见。
 *   AC-21  套餐页弹窗菜单树/能力目录打开时后台刷新（每次打开弹窗重新拉取 /menus 与 /capabilities，
 *          非挂载期 useRef 永久缓存；重开可见新数据）。
 *
 * 装配照范本 web/tests/REQ20261124213008-adminFrontendPages.test.tsx。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 jsdom 无真实布局：几何断言（不撑高/footer 可视）以「限高滚动容器样式声明存在（maxHeight+
 *       overflowY auto/scroll）且包含树/能力节点」为数据驱动代理；真实滚动与视觉由浏览器实测确认
 *       （映射表已注明为浏览器实测项，与 AC-9 同口径）。
 *   A-2 绑定菜单弹窗入口=行内「绑定菜单」按钮；弹窗内同时含菜单树与能力复选组（AC-24 原文括注）。
 *   A-3 实现未落地属预期红（钉死事实⑥）：限高滚动/目录打开时刷新均为本 REQ 新增面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { TenantPackagesPage } from '../src/pages/TenantPackagesPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

const CANCEL_BUTTON = /取\s*消|^取消$|^Cancel$/i;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
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

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

const currentDialog = () => {
  const dialogs = screen.getAllByRole('dialog');
  return dialogs[dialogs.length - 1] as HTMLElement;
};

/** 向上找最近的限高滚动容器（maxHeight + overflowY auto/scroll，A-1 代理判据）。
 *  B-组兼容修复：jsdom cssstyle 不展开 overflow 简写（overflowY 读空），兼读 overflow 短手声明。 */
const findBoundedScrollAncestor = (el: Element): HTMLElement | null => {
  let node: HTMLElement | null = el instanceof HTMLElement ? el : el.parentElement;
  while (node) {
    const overflowY = node.style.overflowY || node.style.overflow;
    const maxHeight = node.style.maxHeight;
    if (/auto|scroll/.test(overflowY) && maxHeight) return node;
    node = node.parentElement;
  }
  return null;
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
const SF_PKG_A = '9223372036854775941';
const SF_MENU_DIR = '9223372036854775911';
const SF_MENU_PAGE = '9223372036854775912';

const MENU_TREE = [
  {
    id: SF_MENU_DIR, parentId: null, menuName: '系统管理', menuType: 'DIRECTORY', path: null, component: null,
    icon: null, sortOrder: 1, visible: true, permissionCode: null, version: '0',
    children: [
      { id: SF_MENU_PAGE, parentId: SF_MENU_DIR, menuName: '用户管理', menuType: 'MENU', path: '/users', component: null, icon: null, sortOrder: 1, visible: true, permissionCode: 'system:user:list', version: '0', children: [] },
    ],
  },
  { id: '9223372036854775919', parentId: null, menuName: '工作台', menuType: 'MENU', path: '/dashboard', component: null, icon: null, sortOrder: 2, visible: true, permissionCode: null, version: '0', children: [] },
];

const CAPS = [
  // B-组 fixture 修复：wire 契约字段为 capabilityCode（AdminCapabilityView，REQ-20261121-054613）——
  // 原用例写 capabilityKey 致选项 value=undefined，antd Checkbox.Group 渲染崩溃整树卸载
  { id: '8101', capabilityCode: 'bpm', capabilityName: '流程引擎', status: 'ENABLED' },
  { id: '8102', capabilityCode: 'files', capabilityName: '文件服务', status: 'ENABLED' },
];

const buildRoutes = (state: { menuRows: typeof MENU_TREE; capRows: typeof CAPS }) => ({
  '/system/tenant-packages': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET' && r.url.pathname.endsWith('/tenant-packages')) {
      return envelope({ records: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }], total: 1 });
    }
    return envelope({ id: SF_PKG_A, packageName: '基础套餐', remark: null, menuIds: [SF_MENU_DIR, SF_MENU_PAGE] });
  },
  '/system/menus': () => envelope(state.menuRows),
  '/system/capabilities': () => envelope(state.capRows),
});

const PERMS_SEED = ['system:tenant-package:list', 'system:tenant-package:create', 'system:tenant-package:update'];

const openBindMenuModal = async (): Promise<HTMLElement> => {
  const row = await findRowByText('基础套餐');
  fireEvent.click(await within(row).findByText(/绑\s*定\s*菜\s*单/));
  await waitFor(() => {
    expect(document.querySelector('.ant-modal-body'), '绑定菜单弹窗应打开').toBeTruthy();
  });
  return currentDialog();
};

// ————— AC-24：弹窗限高滚动 —————
describe('REQ20261125230807 TenantPackagesPage 绑定菜单弹窗限高（AC-24）', () => {
  it('should_constrain_tree_in_bounded_scroll_container_with_footer_visible_when_menu_tree_expanded', async () => {
    await renderPage(TenantPackagesPage, PERMS_SEED, buildRoutes({ menuRows: MENU_TREE, capRows: CAPS }));
    const dialog = await openBindMenuModal();

    // 菜单树全量展开后节点可见（A-2：默认全展；未展开则点开父节点）
    const parentTitle = await waitFor(() => {
      const el = Array.from(dialog.querySelectorAll('[class*="-tree-title"]')).find((node) => (node.textContent ?? '').trim() === '系统管理');
      expect(el, '菜单树应渲染').toBeTruthy();
      return el as HTMLElement;
    });
    const parentNode = (parentTitle.closest('[class*="-tree-treenode"]') ?? parentTitle.closest('li')) as HTMLElement;
    const parentSwitcher = parentNode.querySelector('[class*="-tree-switcher"]') as HTMLElement | null;
    if (parentSwitcher && /close/.test(parentSwitcher.className)) fireEvent.click(parentSwitcher);
    await waitFor(() => {
      expect(Array.from(dialog.querySelectorAll('[class*="-tree-title"]')).some((node) => (node.textContent ?? '').trim() === '用户管理'), '子节点应可见（全量展开）').toBe(true);
    });

    // 树区域在限高滚动容器内（A-1 代理判据：maxHeight + overflowY 滚动）
    const treeContainer = findBoundedScrollAncestor(parentTitle);
    expect(treeContainer, '树区域应位于限高滚动容器内（maxHeight+overflowY；浏览器实测项见映射表）').toBeTruthy();

    // 能力复选组存在且同受限高约束（自身容器或同一滚动容器）
    const capNode = await waitFor(() => {
      const el = Array.from(dialog.querySelectorAll('label, .ant-checkbox-wrapper')).find((node) => (node.textContent ?? '').includes('流程引擎'));
      expect(el, '能力复选组应渲染（流程引擎）').toBeTruthy();
      return el as HTMLElement;
    });
    const capContainer = findBoundedScrollAncestor(capNode);
    expect(capContainer === treeContainer || capContainer !== null, '能力复选组应同受限高滚动约束').toBe(true);

    // footer 按钮始终可见（代理判据：footer 在弹窗节点内持续存在且不含滚动容器外抛）
    const footer = dialog.querySelector('.ant-modal-footer') as HTMLElement | null;
    expect(footer, '弹窗 footer 应存在（按钮始终可见；视觉滚动由浏览器实测）').toBeTruthy();
    expect(footer!.textContent ?? '').toMatch(/确\s*定|保\s*存/);
    expect(footer!.textContent ?? '').toMatch(/取\s*消/);
  });
});

// ————— AC-21（套餐弹窗半句）：菜单树/能力目录打开时后台刷新 —————
describe('REQ20261125230807 套餐弹窗目录后台刷新（AC-21 套餐半句）', () => {
  it('should_refetch_menu_tree_and_capabilities_when_bind_modal_reopened', async () => {
    const state = { menuRows: MENU_TREE, capRows: CAPS };
    const { fetchMock } = await renderPage(TenantPackagesPage, PERMS_SEED, buildRoutes(state));
    await findRowByText('基础套餐');

    // 第一次打开：菜单树 + 能力目录各拉一次
    let dialog = await openBindMenuModal();
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/menus').length).toBeGreaterThanOrEqual(1);
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/capabilities').length).toBeGreaterThanOrEqual(1);
    });
    const menuCallsAfterFirst = callsOf(fetchMock, 'GET', '/admin/v1/system/menus').length;
    const capCallsAfterFirst = callsOf(fetchMock, 'GET', '/admin/v1/system/capabilities').length;

    // 关闭（数据驱动：不断言 DOM 卸载）
    const cancel = await within(dialog).findByRole('button', { name: CANCEL_BUTTON }).catch(() => within(dialog).getByRole('button', { name: /关\s*闭/ }));
    fireEvent.click(cancel);

    // mock 侧目录演进（页签存续期间菜单/能力有新增）
    state.menuRows = [
      ...MENU_TREE,
      { id: '9223372036854775925', parentId: null, menuName: '报表中心', menuType: 'MENU', path: '/reports', component: null, icon: null, sortOrder: 3, visible: true, permissionCode: null, version: '0', children: [] },
    ];
    state.capRows = [...CAPS, { id: '8103', capabilityCode: 'notice', capabilityName: '消息中心', status: 'ENABLED' }];

    // 不刷新直接重开弹窗 → 目录后台重新拉取且新数据可见（非 useRef 永久缓存）
    fireEvent.click(await within(await findRowByText('基础套餐')).findByText(/绑\s*定\s*菜\s*单/));
    dialog = await currentDialog();
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/menus').length).toBeGreaterThan(menuCallsAfterFirst);
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/capabilities').length).toBeGreaterThan(capCallsAfterFirst);
    });
    await waitFor(() => {
      expect(dialog.textContent ?? '', '重开弹窗应见到新增菜单').toContain('报表中心');
      expect(dialog.textContent ?? '', '重开弹窗应见到新增能力').toContain('消息中心');
    });
  });
});
