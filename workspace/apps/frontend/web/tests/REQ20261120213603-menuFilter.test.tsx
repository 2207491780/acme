/**
 * REQ-20261120-213603 菜单管理筛选——页面级测试（AC-10~13）。
 *
 * 契约口径（contracts/REQ-20261120-213603.md §AC-10~13）：菜单管理页启用筛选——
 * 关键字搜索框＋菜单类型/状态/可见性三个下拉＋重置入口（参照 UsersPage 筛选交互形态，文案走 i18n zh/en）；
 * 提交筛选携带参数且空值不进查询串；重置恢复全量树；筛选空结果展示表格空态。
 *
 * 装配照既有范本 web/tests/REQ20261119051119-menusButton.test.tsx（fetch 弱匹配 + 权限播种 + AntApp）
 * ＋ REQ20261114120104-usersPageDeptFilter.test.tsx（下拉筛选驱动/选项点选）；
 * 筛选区/关键词/重置文案取 CrudTable·FilterPanel 既有口径（REQ20261117125500-filterPanel.test.tsx）。
 * mock 侧实现契约命中语义（命中∪祖先链），使「表格展示筛选后树」可断言。
 *
 * 【表面假定（低风险，失败即交 TL 裁决）】
 *   A-1 页面 web/src/pages/MenusPage.tsx 命名导出 MenusPage（既有页，menusButton 口径）。
 *   A-2 筛选区为 CrudTable/FilterPanel 既有形态：region「筛选条件」；关键字输入 label「关键词」；
 *       重置按钮 /重\s*置/；若有「展开进阶筛选」开关则三个下拉可能收在其下——统一先展开再定位。
 *   A-3 三个下拉的承载文案（placeholder 或 label 或 aria-label）含「类型 / 状态 / 可见」字样
 *       （AC-10 措辞的自然承载；选项文案：停用 /隐藏 /按钮——正则子串匹配兼容后缀变体）。
 *   A-4 i18n：文案随语言切换（zh 与 en 下三下拉承载文案不全等）；默认测试语言 zh-CN。
 *   A-5 交互：关键字 Enter 提交（UsersPage/CrudTable 口径）；下拉选中即触发筛选（UsersPage 部门筛选口径，
 *       选中后立即发列表请求）；空值不进查询串（useCrud 既有语义）。
 *   A-6 权限点 system:menu:list（menusButton 口径）；列表请求 URL 含 '/menus'。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore } from '@acme/core';
import { MenusPage } from '../src/pages/MenusPage';

vi.setConfig({ testTimeout: 25000 });

const MENU_PATH_PART = '/menus';
const KEYWORD_LABEL = /关\s*键\s*词|关键字|Keyword/i;
const RESET_BUTTON = /重\s*置|Reset/i;
const TYPE_SELECT = /类\s*型|Type/i;
const STATUS_SELECT = /状\s*态|Status/i;
const VISIBLE_SELECT = /可\s*见|Visib/i;
// 选项点选用精确文案（pickOption 结构性遍历＋精确相等，不用文本正则——
// 下拉容器内的 a11y 镜像节点与表格「停用/隐藏」标签都会令文本类匹配双命中）；
// BUTTON_OPTION=core i18n menus.type.buttonOption 实际渲染值（zh-CN/menus.ts:31，带说明后缀）
const DISABLED_OPTION = '停用';
const HIDDEN_OPTION = '隐藏';
const BUTTON_OPTION = '按钮（操作权限）';

const PERM_LIST = ['system:menu:list'];

// ————— fixtures（根目录 + 三维度样叶子 + 按钮孙节点；隐藏/停用样例承载 AC-4/AC-5 前端面） —————
type MenuFixture = Record<string, unknown>;

const menuNode = (over: MenuFixture = {}): MenuFixture => ({
  id: '0', parentId: '0', menuName: '', menuType: 'MENU', path: null, component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

const ROOT = menuNode({
  id: '8801', parentId: '0', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', sortOrder: 1,
});
const USERS_PAGE = menuNode({
  id: '8802', parentId: '8801', menuName: '菜单筛选-用户管理', menuType: 'MENU', path: '/mf-users',
  sortOrder: 1, permissionCode: 'system:user:list',
});
const HIDDEN_PAGE = menuNode({
  id: '8803', parentId: '8801', menuName: '菜单筛选-隐藏页', menuType: 'MENU', path: '/mf-hidden',
  sortOrder: 2, visible: false,
});
const DISABLED_PAGE = menuNode({
  id: '8804', parentId: '8801', menuName: '菜单筛选-停用页', menuType: 'MENU', path: '/mf-disabled',
  sortOrder: 3, status: 'DISABLED',
});
const CREATE_BTN = menuNode({
  id: '8805', parentId: '8802', menuName: '菜单筛选-新建按钮', menuType: 'BUTTON', path: null,
  sortOrder: 1, permissionCode: 'system:user:create',
});
const FULL_TREE: MenuFixture[] = [
  {
    ...ROOT,
    children: [
      { ...USERS_PAGE, children: [CREATE_BTN] },
      HIDDEN_PAGE,
      DISABLED_PAGE,
    ],
  },
];

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

// ————— mock 侧命中语义（契约镜像：命中节点 ∪ 全部祖先；命中节点的子孙不自动带出） —————
const flatten = (nodes: MenuFixture[], out: MenuFixture[] = []): MenuFixture[] => {
  nodes.forEach((node) => {
    out.push(node);
    flatten((node.children as MenuFixture[]) ?? [], out);
  });
  return out;
};

const hitOf = (node: MenuFixture, query: URLSearchParams): boolean => {
  const keyword = query.get('keyword');
  if (keyword !== null) {
    const hay = [node.menuName, node.path, node.permissionCode]
      .filter((value): value is string => typeof value === 'string')
      .map((value) => value.toLowerCase());
    if (!hay.some((value) => value.includes(keyword.toLowerCase()))) return false;
  }
  const menuType = query.get('menuType');
  if (menuType !== null && node.menuType !== menuType) return false;
  const status = query.get('status');
  if (status !== null && node.status !== status) return false;
  const visible = query.get('visible');
  if (visible !== null && Boolean(node.visible) !== (visible === 'true')) return false;
  return true;
};

const filterTree = (query: URLSearchParams): MenuFixture[] => {
  const flat = flatten(FULL_TREE);
  const keep = new Set<string>();
  flat.filter((node) => hitOf(node, query)).forEach((hit) => {
    let current: MenuFixture | undefined = hit;
    while (current) {
      keep.add(String(current.id));
      const parentId: string = String(current.parentId);
      current = parentId === '0' ? undefined : flat.find((node) => String(node.id) === parentId);
    }
  });
  const rebuild = (nodes: MenuFixture[]): MenuFixture[] =>
    nodes
      .filter((node) => keep.has(String(node.id)))
      .map((node) => ({ ...node, children: rebuild((node.children as MenuFixture[]) ?? []) }));
  return rebuild(FULL_TREE);
};

const setupMenusApi = () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes(MENU_PATH_PART)) return envelope(null);
    if (r.method === 'GET') {
      const records = filterTree(r.url.searchParams);
      return envelope({ records, total: records.length });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

const menuGets = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as [input: RequestInfo | URL, init?: RequestInit][])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(MENU_PATH_PART) && r.method === 'GET');

const lastMenuQuery = (fetchMock: ReturnType<typeof vi.fn>): URLSearchParams | null =>
  menuGets(fetchMock).at(-1)?.url.searchParams ?? null;

// ————— 装配 —————
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

const renderPage = async () => {
  await seedMe(PERM_LIST);
  const fetchMock = setupMenusApi();
  render(<AntApp><MenusPage /></AntApp>);
  return fetchMock;
};

const expandAdvancedFiltersIfPresent = () => {
  const toggle = screen.queryByRole('button', { name: '展开进阶筛选' });
  if (toggle) fireEvent.click(toggle);
};

/** 下拉承载文案（A-3：placeholder / 选择区占位 / label 关联 / aria-label 兼容查找）。 */
const selectDescriptor = (select: Element): string => {
  const input = select.querySelector('input');
  const placeholderNode = select.querySelector('.ant-select-selection-placeholder');
  const labelText = input?.id
    ? document.querySelector<HTMLLabelElement>(`label[for="${input.id}"]`)?.textContent ?? null
    : null;
  return (
    input?.getAttribute('placeholder')
    ?? placeholderNode?.textContent
    ?? labelText
    ?? input?.getAttribute('aria-label')
    ?? select.getAttribute('aria-label')
    ?? ''
  ).trim();
};

const filterPanel = async (): Promise<HTMLElement> => {
  expandAdvancedFiltersIfPresent();
  return await screen.findByRole('region', { name: '筛选条件' }, { timeout: 5000 });
};

const findSelectInPanel = async (panel: HTMLElement, pattern: RegExp): Promise<HTMLElement> => {
  const match = Array.from(panel.querySelectorAll('.ant-select'))
    .find((select) => pattern.test(selectDescriptor(select)));
  if (!match) {
    throw new Error(`筛选下拉未找到（pattern=${pattern}）；实际承载文案=[${
      Array.from(panel.querySelectorAll('.ant-select')).map(selectDescriptor).join(' | ')}]`);
  }
  return match as HTMLElement;
};

/** 打开下拉：antd v5（rc-select）的 mousedown 开启处理器挂在内层 .ant-select-selector 上，
 * 打在 .ant-select 根元素不传后代、永不触发（usersPageDeptFilter 口径；回退搜索输入框，兜底原元素）。 */
const openSelectDropdown = (select: HTMLElement) => {
  fireEvent.mouseDown(
    select.querySelector('.ant-select-selector')
    ?? select.querySelector('.ant-select-selection-search-input')
    ?? select,
  );
};

/** 选项点选：结构性遍历打开下拉内的 .ant-select-item-option（a11y 镜像节点不带该 class，结构性排除），
 * 以 option-content 精确相等匹配后点选选项元素；不用 getByText/正则（容器内文本类匹配必然双命中）。 */
const pickOption = async (optionText: string) => {
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toBeTruthy();
  }, { timeout: 5000 });
  const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')!;
  const target = Array.from(dropdown.querySelectorAll('.ant-select-item-option')).find(
    (opt) => opt.querySelector('.ant-select-item-option-content')?.textContent?.trim() === optionText,
  );
  if (!target) {
    throw new Error(`option not found: ${optionText}；实际选项=[${
      Array.from(dropdown.querySelectorAll('.ant-select-item-option'))
        .map((opt) => opt.querySelector('.ant-select-item-option-content')?.textContent?.trim())
        .join(' | ')}]`);
  }
  fireEvent.click(target);
};

const expandRootRow = async () => {
  const cell = await screen.findByText('系统管理', {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  const icon = row.querySelector('.ant-table-row-expand-icon');
  if (icon && !icon.className.includes('ant-table-row-expand-icon-expanded')) {
    fireEvent.click(icon);
  }
};

const expandRowOf = async (anchor: string) => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  const icon = row.querySelector('.ant-table-row-expand-icon');
  if (icon && !icon.className.includes('ant-table-row-expand-icon-expanded')) {
    fireEvent.click(icon);
  }
};

const expectNoFilterParam = (query: URLSearchParams | null, key: string) => {
  expect(query?.has(key), `空值不进查询串：${key}`).toBe(false);
};

beforeEach(async () => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  await i18n.changeLanguage('zh-CN');
  stubBrowserGaps();
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  await i18n.changeLanguage('zh-CN');
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261120213603 MenusPage 菜单筛选（AC-10~13）', () => {
  // AC-10：筛选 UI 结构 + i18n 文案（zh/en）
  it('should_render_keyword_search_three_selects_and_reset_when_page_loads', async () => {
    await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });

    const panel = await filterPanel();
    // 关键字搜索框（CrudTable 既有文案「关键词」）
    expect(within(panel).getByLabelText(KEYWORD_LABEL)).toBeTruthy();
    // 三个下拉（菜单类型/状态/可见性）：按维度文案各定位一个，且互不相同
    const byPattern = (pattern: RegExp) => findSelectInPanel(panel, pattern);
    const typeSelect = await byPattern(TYPE_SELECT);
    const statusSelect = await byPattern(STATUS_SELECT);
    const visibleSelect = await byPattern(VISIBLE_SELECT);
    expect(new Set([typeSelect, statusSelect, visibleSelect]).size, '三下拉为三个独立控件').toBe(3);
    // 三下拉均有承载文案（i18n 文案存在）
    const zhDescriptors = [typeSelect, statusSelect, visibleSelect].map(selectDescriptor).sort();
    expect(zhDescriptors.every((text) => text.length > 0)).toBe(true);
    // 重置入口
    expect(screen.getByRole('button', { name: RESET_BUTTON })).toBeTruthy();

    // i18n：切 en 后三下拉承载文案与 zh 不全等（文案走 i18n 而非硬编码；en 定位用同族英文词根）
    await act(() => i18n.changeLanguage('en-US'));
    const enPanel = await screen.findByRole('region', { name: /筛选条件|Filter criteria/i }, { timeout: 5000 });
    const enDescriptors = (
      await Promise.all([
        findSelectInPanel(enPanel, TYPE_SELECT),
        findSelectInPanel(enPanel, STATUS_SELECT),
        findSelectInPanel(enPanel, VISIBLE_SELECT),
      ])
    ).map(selectDescriptor).sort();
    expect(enDescriptors.join('|'), 'en 文案应与 zh 不同（文案走 i18n）').not.toBe(zhDescriptors.join('|'));
    expect(enDescriptors.every((text) => text.length > 0)).toBe(true);
  });

  // AC-11a：关键字提交 → 携带 keyword，表格展示命中∪祖先（子孙不自动带出）
  it('should_request_menus_with_keyword_when_search_submitted', async () => {
    const fetchMock = await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });
    const panel = await filterPanel();

    const input = within(panel).getByLabelText(KEYWORD_LABEL) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '菜单筛选-用户管理' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => {
      expect(lastMenuQuery(fetchMock)?.get('keyword')).toBe('菜单筛选-用户管理');
    });

    // 筛选后树：命中节点 + 祖先链；命中节点的子孙（新建按钮）不自动带出
    await expandRootRow();
    expect(await screen.findByText('菜单筛选-用户管理', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('菜单筛选-新建按钮'), '子孙不自动带出').toBeNull();
    expect(screen.queryByText('菜单筛选-隐藏页')).toBeNull();
    expect(screen.queryByText('菜单筛选-停用页')).toBeNull();
  });

  // AC-11b：状态下拉选中 → 立即携带 status，其余空值不进查询串
  it('should_request_menus_with_status_when_select_picked', async () => {
    const fetchMock = await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });
    const panel = await filterPanel();

    openSelectDropdown(await findSelectInPanel(panel, STATUS_SELECT));
    await pickOption(DISABLED_OPTION);
    await waitFor(() => {
      const query = lastMenuQuery(fetchMock);
      expect(query?.get('status')).toBe('DISABLED');
      expectNoFilterParam(query, 'keyword');
      expectNoFilterParam(query, 'menuType');
      expectNoFilterParam(query, 'visible');
    });

    // 筛选后树：停用节点 + 祖先链
    await expandRootRow();
    expect(await screen.findByText('菜单筛选-停用页', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('菜单筛选-隐藏页')).toBeNull();
    expect(screen.queryByText('菜单筛选-用户管理')).toBeNull();
  });

  // AC-11c：可见性下拉选中 → visible=false 参数序列化
  it('should_request_menus_with_visible_false_when_select_picked', async () => {
    const fetchMock = await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });
    const panel = await filterPanel();

    openSelectDropdown(await findSelectInPanel(panel, VISIBLE_SELECT));
    await pickOption(HIDDEN_OPTION);
    await waitFor(() => {
      const query = lastMenuQuery(fetchMock);
      expect(query?.get('visible')).toBe('false');
      expectNoFilterParam(query, 'keyword');
      expectNoFilterParam(query, 'menuType');
      expectNoFilterParam(query, 'status');
    });

    await expandRootRow();
    expect(await screen.findByText('菜单筛选-隐藏页', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('菜单筛选-停用页')).toBeNull();
  });

  // AC-11d：类型下拉选中 → menuType=BUTTON，按钮节点经祖先链挂到页面菜单下
  it('should_request_menus_with_menu_type_button_when_select_picked', async () => {
    const fetchMock = await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });
    const panel = await filterPanel();

    openSelectDropdown(await findSelectInPanel(panel, TYPE_SELECT));
    await pickOption(BUTTON_OPTION);
    await waitFor(() => {
      const query = lastMenuQuery(fetchMock);
      expect(query?.get('menuType')).toBe('BUTTON');
      expectNoFilterParam(query, 'keyword');
      expectNoFilterParam(query, 'status');
      expectNoFilterParam(query, 'visible');
    });

    await expandRootRow();
    await expandRowOf('菜单筛选-用户管理');
    expect(await screen.findByText('菜单筛选-新建按钮', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('菜单筛选-隐藏页')).toBeNull();
  });

  // AC-12：重置 → 四参数清空（空值不进查询串）+ 恢复全量树
  it('should_restore_full_tree_when_reset_clicked', async () => {
    const fetchMock = await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });
    const panel = await filterPanel();

    // 先应用状态筛选
    openSelectDropdown(await findSelectInPanel(panel, STATUS_SELECT));
    await pickOption(DISABLED_OPTION);
    await waitFor(() => expect(lastMenuQuery(fetchMock)?.get('status')).toBe('DISABLED'));

    // 重置：查询串不再携带任何筛选参数
    fireEvent.click(screen.getByRole('button', { name: RESET_BUTTON }));
    await waitFor(() => {
      const query = lastMenuQuery(fetchMock);
      ['keyword', 'menuType', 'status', 'visible'].forEach((key) => expectNoFilterParam(query, key));
    });

    // 恢复全量树：根下三个子行 + 页面菜单下的按钮行均在
    await expandRootRow();
    expect(await screen.findByText('菜单筛选-用户管理', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByText('菜单筛选-隐藏页')).toBeTruthy();
    expect(screen.getByText('菜单筛选-停用页')).toBeTruthy();
    await expandRowOf('菜单筛选-用户管理');
    expect(await screen.findByText('菜单筛选-新建按钮', {}, { timeout: 5000 })).toBeTruthy();
  });

  // AC-13：筛选空结果 → 表格空态提示
  it('should_show_table_empty_state_when_filter_hits_nothing', async () => {
    const fetchMock = await renderPage();
    await screen.findByText('系统管理', {}, { timeout: 5000 });
    const panel = await filterPanel();

    const input = within(panel).getByLabelText(KEYWORD_LABEL) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '菜单筛选-无命中xyz' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => {
      expect(lastMenuQuery(fetchMock)?.get('keyword')).toBe('菜单筛选-无命中xyz');
    });
    // 表格空态行（antd Table placeholder + empty 插画），数据行清空
    await waitFor(() => {
      expect(document.querySelector('.ant-table-placeholder')).toBeTruthy();
      expect(document.querySelector('.ant-table-placeholder .ant-empty')).toBeTruthy();
    });
    expect(screen.queryByText('菜单筛选-用户管理')).toBeNull();
    expect(screen.queryByText('系统管理')).toBeNull();
  });
});
