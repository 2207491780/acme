/**
 * REQ-20261127-234158（wire 契约·DTO 字段对齐 P2-02）菜单树字符串 id 行为级用例（AC-7）。
 *
 * 【行为级，非对照】与 frontend-dev 已交付的 web/tests/REQ20261127234158-schedulerMenuWireContract.test.ts
 * （非 MSW 纯数据 wire 对照，AC-9 FE fixture 侧锚）互补：本文件从「渲染与提交链路」视角断言——
 *   ① GET /admin/v1/system/menus 真实 wire 形态（id/parentId/version 均 JSON 字符串、根 parentId="0"、
 *      children 嵌套）下 MenusPage 树表正确渲染：根行呈现且可展开（根判定 parentId === '0' 生效），
 *      字符串 id 父子装配生效（子行/按钮孙行展开可见）；
 *   ② 新建根菜单提交载荷 parentId 为 null（menuCreate.parentId?: string | null，不得 '0'/数值 0）；
 *   ③ menuUpdate/menuDelete 路径段 menuId 保持字符串原样。
 *
 * 钉死 wire golden（事实源=backend REQ20261127234158*WireTest.java 的 AdminMenuView 序列化用例）：
 *   - AdminMenuView：id/parentId/version 均 JSON 字符串（LongToStringModule）；根节点 parentId="0"
 *     （字符串零，非 null）；menuType 枚举名 DIRECTORY|MENU|BUTTON；status 枚举名；children 嵌套。
 *
 * 装配照既有范本 web/tests/REQ20261119051119-menusButton.test.tsx（MenusPage 直渲染：fetch 弱匹配
 * + 权限播种 + AntApp）与 web/tests/REQ20261125230807-snowflakeApiPayload.test.tsx（systemApi 提交侧
 * fetch 桩断言口径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { systemApi, useAuthStore, useMeStore } from '@acme/core';
import { MenusPage } from '../src/pages/MenusPage';

vi.setConfig({ testTimeout: 60000 });

const MENU_PATH_PART = '/admin/v1/system/menus';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as [input: RequestInfo | URL, init?: RequestInit][])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

// ————— fixtures（真实 wire 形态：id/parentId/version 字符串、根 parentId="0"、children 嵌套） —————
type MenuFixture = Record<string, unknown>;

const menuNode = (over: MenuFixture = {}): MenuFixture => ({
  id: '0', parentId: '0', menuName: '', menuType: 'MENU', path: null, component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

const WIRE_MENU_TREE: MenuFixture[] = [
  menuNode({
    id: '8801', parentId: '0', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system',
    version: '0',
    children: [
      menuNode({
        id: '8802', parentId: '8801', menuName: '菜单树-用户中心', menuType: 'MENU', path: '/wire-users',
        permissionCode: 'system:user:list', version: '0',
        children: [
          menuNode({
            id: '8803', parentId: '8802', menuName: '菜单树-用户新建', menuType: 'BUTTON', path: null,
            permissionCode: 'system:user:create', version: '3',
          }),
        ],
      }),
    ],
  }),
];

const setupMenusApi = () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes(MENU_PATH_PART)) return envelope(null);
    if (r.method === 'GET') {
      return envelope({ records: WIRE_MENU_TREE.map((node) => ({ ...node })), total: WIRE_MENU_TREE.length });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

// ————— 装配（范本口径） —————
const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const seedMe = async (permissions: string[]) => {
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

const rowOf = async (anchor: string): Promise<HTMLElement> => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  return cell.closest('tr') as HTMLElement;
};

/** 展开行（树表 expand icon 已展开则跳过；范本口径）。 */
const expandRowOf = async (anchor: string): Promise<void> => {
  const row = await rowOf(anchor);
  const icon = row.querySelector('.ant-table-row-expand-icon');
  if (icon && !icon.className.includes('ant-table-row-expand-icon-expanded')) {
    fireEvent.click(icon);
  }
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

// ————— ① 真实 wire 形态树渲染：根 parentId="0" 根判定生效 + 字符串 id 父子装配 —————
describe('REQ20261127234158 菜单树字符串 id 渲染（AC-7）', () => {
  it('should_render_wire_menu_tree_with_string_ids_when_root_parent_id_is_zero_string', async () => {
    await seedMe(['system:menu:list']);
    const fetchMock = setupMenusApi();
    render(<AntApp><MenusPage /></AntApp>);

    // wire 端点：GET /admin/v1/system/menus（字符串 id 口径）
    await waitForIncludeGet(fetchMock);

    // 根节点 parentId="0"（字符串零）渲染为顶层行（根判定 parentId === '0' 生效；数值 0/null 判定即红）
    const rootRow = await rowOf('系统管理');
    expect(rootRow.closest('tbody'), '根节点应渲染为表格顶层行').toBeTruthy();
    expect(rootRow.querySelector('.ant-table-row-expand-icon'), '根行可展开（children 经字符串 id 装配）').toBeTruthy();

    // 节点 id 字符串：子行经字符串 parentId（"8801"）挂到字符串 id 根（"8801"）下
    await expandRowOf('系统管理');
    expect(await screen.findByText('菜单树-用户中心', {}, { timeout: 5000 })).toBeTruthy();

    // 字符串 id 孙节点装配：BUTTON 行呈现 + 「按钮」Tag
    await expandRowOf('菜单树-用户中心');
    expect(await screen.findByText('菜单树-用户新建', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByText('按钮')).toBeTruthy();
  });
});

/** 轮询等待出现包含 MENU_PATH_PART 的 GET 调用（菜单列表请求）。 */
async function waitForIncludeGet(fetchMock: ReturnType<typeof vi.fn>): Promise<void> {
  const start = Date.now();
  for (;;) {
    if (callsOf(fetchMock, 'GET', MENU_PATH_PART).length > 0) return;
    if (Date.now() - start > 5000) {
      throw new Error(`菜单列表 GET ${MENU_PATH_PART} 未发出`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

// ————— ②/③ menuCreate/menuUpdate/menuDelete 提交侧（AC-7：parentId null 口径 + menuId 字符串路径段） —————
describe('REQ20261127234158 菜单提交侧字符串 id（AC-7）', () => {
  it('should_send_null_parent_id_when_root_menu_created', async () => {
    const fetchMock = vi.fn(async () => envelope(null));
    vi.stubGlobal('fetch', fetchMock);

    // 新建根菜单：parentId 为 null（menuCreate.parentId?: string | null；不得 '0'/数值 0 哨兵混用）
    await systemApi.menuCreate({
      parentId: null,
      menuName: '菜单树-新建根菜单',
      menuType: 'DIRECTORY',
      sortOrder: 9,
    });

    const posts = callsOf(fetchMock, 'POST', MENU_PATH_PART);
    expect(posts, '新建菜单应发出 POST /admin/v1/system/menus').toHaveLength(1);
    const body = parseBody(posts[0]?.body);
    expect(body.parentId ?? null, '根菜单 parentId 应为 null（或缺省）').toBeNull();
    expect(body.parentId, '根菜单 parentId 不得为字符串零哨兵').not.toBe('0');
    expect(body.parentId, '根菜单 parentId 不得为数值 0').not.toBe(0);
    expect(body.menuName).toBe('菜单树-新建根菜单');
    expect(body.menuType).toBe('DIRECTORY');
  });

  it('should_preserve_string_parent_id_when_child_menu_created', async () => {
    const fetchMock = vi.fn(async () => envelope(null));
    vi.stubGlobal('fetch', fetchMock);

    // 新建子菜单：parentId 字符串原样（不得经 Number 数值化丢精度）
    await systemApi.menuCreate({
      parentId: '8801',
      menuName: '菜单树-新建子菜单',
      menuType: 'MENU',
      sortOrder: 2,
    });

    const body = parseBody(callsOf(fetchMock, 'POST', MENU_PATH_PART)[0]?.body);
    expect(body.parentId, '子菜单 parentId 应为字符串原样').toBe('8801');
  });

  it('should_carry_string_menu_id_in_path_when_menu_updated_and_deleted', async () => {
    const fetchMock = vi.fn(async () => envelope(null));
    vi.stubGlobal('fetch', fetchMock);

    // menuUpdate：menuId 字符串路径段原样
    await systemApi.menuUpdate('8802', {
      menuName: '菜单树-用户中心', sortOrder: 2, visible: true, version: '0',
    });
    const puts = callsOf(fetchMock, 'PUT', MENU_PATH_PART);
    expect(puts).toHaveLength(1);
    expect(puts[0]?.url.pathname, 'menuUpdate 路径段应为字符串 menuId 原样').toBe(`${MENU_PATH_PART}/8802`);

    // menuDelete：menuId 字符串路径段原样
    await systemApi.menuDelete('8803');
    const deletes = callsOf(fetchMock, 'DELETE', MENU_PATH_PART);
    expect(deletes).toHaveLength(1);
    expect(deletes[0]?.url.pathname, 'menuDelete 路径段应为字符串 menuId 原样').toBe(`${MENU_PATH_PART}/8803`);
  });
});
