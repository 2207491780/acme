/**
 * REQ-20261114-120101 AC-7：导航动态化——登录后按 me.menus 菜单树渲染导航（菜单树+权限）；
 * me.menus 缺失/为空时回退静态 NAV（fail-closed 不变）。
 *
 * me.menus 节点口径 = AdminMenuView（platform-system-api DTO，wire 形状）：
 *   id/parentId/menuName/menuType(DIRECTORY|MENU)/path/component/icon/sortOrder/visible/
 *   permissionCode/status(ENABLED)/children/version。
 *
 * 装配：hash 路由（web 既有口径）+ useAuthStore.setSession 播种会话（meStore.test 范本）+
 * useMeStore 播种画像（手动 load，App 若自行 load 亦命中同一 mock）+ <AntApp> 包裹。
 *
 * 【表面假定（待 TL 裁决）】
 *   A-21 web/src/App.tsx 命名导出 App（对齐页面命名导出惯例）
 *   A-22 菜单显示文本 = menuName；权限过滤 = 节点 permissionCode 与 me.permissions 同源比对
 *       （fixture 两处播种完全相同字面量，任何归一化方案下两侧一致变换 → 显隐判定稳定）
 *   A-23 visible=false 节点不渲染；DIRECTORY 容器渲染且可含子项
 *   A-24 静态 NAV 文案未知 → 回退断言为计数式（menuitem ≥2），静态项文本不做假设
 *   A-25 渲染整棵 App：Dashboard 等伴随请求统一 mock 回空信封，不影响导航断言
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { buildDynamicNav } from '../src/nav';
import { getDateLocale, i18n, useAuthStore, useLocaleStore, useMeStore, useTimezoneStore, type AdminMenuView } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

type MenuNode = {
  id: string; parentId: string; menuName: string; menuType: 'DIRECTORY' | 'MENU';
  path: string; component: string | null; icon: string | null; sortOrder: number;
  visible: boolean; permissionCode: string | null; status: 'ENABLED' | 'DISABLED';
  children: MenuNode[]; version: string;
};

const menuNode = (over: Partial<MenuNode> = {}): MenuNode => ({
  id: '1', parentId: '0', menuName: '', menuType: 'MENU', path: '/', component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

/** 两级菜单树：DIRECTORY(系统管理) + MENU 叶子；含未授权节点与 visible=false 节点。 */
const MENUS: MenuNode[] = [
  // 根级 MENU 节点名避开 Dashboard 页面可能出现的标题文案（防 getByText 多匹配）
  menuNode({ id: '1', menuName: '首页概览', menuType: 'MENU', path: '/dashboard', sortOrder: 1 }),
  menuNode({
    id: '2', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', sortOrder: 2,
    children: [
      menuNode({ id: '21', parentId: '2', menuName: '用户中心', path: '/users', permissionCode: 'system:user:list', sortOrder: 1 }),
      menuNode({ id: '22', parentId: '2', menuName: '租户管理', path: '/tenants', permissionCode: 'system:tenant:list', sortOrder: 2 }),
      menuNode({ id: '23', parentId: '2', menuName: '角色管理', path: '/roles', permissionCode: 'system:role:list', sortOrder: 3 }),
      menuNode({ id: '24', parentId: '2', menuName: '隐藏页面', path: '/hidden', visible: false, sortOrder: 4 }),
    ],
  }),
];

const HELD_PERMS = ['system:user:list', 'system:tenant:list'];

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const mePayload = (permissions: string[], menus?: MenuNode[]) => {
  const base: Record<string, unknown> = {
    user: meUser,
    roles: ['super_admin'],
    permissions,
  };
  // menus === undefined 时整键缺失（「缺失」回退分支）
  if (menus !== undefined) base.menus = menus;
  return base;
};

const stubBrowserGaps = () => {
  // 断点全不匹配会把 App 压成移动布局（Sider/Menu 不挂载，A-24 的 menuitem 计数无从谈起）——
  // min-width 断点置 true 使其按桌面布局渲染侧边导航（装配修正，不改断言）
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

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 播种会话 + me 画像（menus 缺省为空数组=「为空」回退分支），再渲染整棵 App。 */
const renderApp = async (permissions: string[], menus: MenuNode[] = []) => {
  seedSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.includes('/system/me')) {
      return envelope(mePayload(permissions, menus));
    }
    return envelope(null); // Dashboard 等伴随请求（A-25）
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = '#/';
  // App 消费 react-router hooks（useNavigate/useLocation），生产装配在 main.tsx 以 HashRouter 包裹——
  // 测试端补齐同一 Router 上下文（装配修复，不改断言）
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const menuItemCount = () => document.querySelectorAll('[role="menuitem"]').length;

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
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261114120101 导航动态化（AC-7）', () => {
  it('should_render_nav_from_me_menu_tree_when_logged_in', async () => {
    await renderApp(HELD_PERMS, MENUS);

    // 菜单树渲染：DIRECTORY 容器与叶子节点均以 menuName 承载（A-22/A-23）
    expect(await screen.findByText('首页概览')).toBeTruthy();
    expect(screen.getByText('系统管理')).toBeTruthy();
    expect(screen.getByText('用户中心')).toBeTruthy();
    expect(screen.getByText('租户管理')).toBeTruthy();
  });

  it('should_hide_menu_nodes_whose_permission_code_not_held', async () => {
    await renderApp(HELD_PERMS, MENUS);

    // 已持有权限的叶子可见，未持有权限（system:role:list）的叶子隐藏
    expect(await screen.findByText('用户中心')).toBeTruthy();
    await waitFor(() => {
      expect(screen.queryByText('角色管理')).toBeNull();
    });
  });

  it('should_hide_menu_nodes_marked_invisible', async () => {
    await renderApp(HELD_PERMS, MENUS);

    expect(await screen.findByText('用户中心')).toBeTruthy();
    // visible=false 的节点不渲染（A-23）
    expect(screen.queryByText('隐藏页面')).toBeNull();
  });

  it('should_fallback_to_static_nav_when_me_menus_empty', async () => {
    // me.menus = [] → 回退静态 NAV（fail-closed 不变；静态项文案不假设，计数式断言 A-24）
    // 播种归一三段式（round1 裁决⑤）
    await renderApp(['system:user:list', 'system:tenant:list'], []);

    await waitFor(() => {
      expect(menuItemCount()).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_fallback_to_static_nav_when_me_menus_key_missing', async () => {
    // me 载荷缺 menus 键 → 同样回退静态 NAV
    // 播种归一三段式（round1 裁决⑤）
    await renderApp(['system:user:list', 'system:tenant:list'], undefined);

    await waitFor(() => {
      expect(menuItemCount()).toBeGreaterThanOrEqual(2);
    });
  });
});

// ————— REQ-20261119-051119：BUTTON 菜单节点显式剔除（纯结构单测，无需渲染 App） —————
// 注意：直接调用 buildDynamicNav，节点须为 AdminMenuView 结构（id number / path 可空），与上方 wire fixture 无关
const btnMenuNode = (over: Partial<AdminMenuView> = {}): AdminMenuView => ({
  id: 31, parentId: 2, menuName: '', menuType: 'BUTTON', path: null, component: null, icon: null,
  sortOrder: 9, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

describe('REQ20261119051119 导航剔除 BUTTON 节点', () => {
  const treeWithButton: AdminMenuView[] = [
    {
      id: 2, parentId: 0, menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', component: null,
      icon: null, sortOrder: 2, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
      children: [
        { id: 21, parentId: 2, menuName: '用户中心', menuType: 'MENU', path: '/users', component: null, icon: null, sortOrder: 1, visible: true, permissionCode: 'system:user:list', status: 'ENABLED', children: [], version: '0' },
        btnMenuNode({ parentId: 2, menuName: '用户新建按钮', permissionCode: 'system:user:create' }),
      ],
    },
  ];

  it('should_exclude_button_nodes_from_nav_entries_and_items_when_menu_tree_contains_button', () => {
    const nav = buildDynamicNav(treeWithButton, ['system:user:list', 'system:user:create']);
    expect(nav).not.toBeNull();
    // BUTTON 节点即便用户持码也不进导航（仅授权树展示）
    expect(nav!.entries.map((e) => e.path)).toEqual(['/users']);
    const serialized = JSON.stringify(nav!.items);
    expect(serialized).not.toContain('用户新建按钮');
    expect(serialized).toContain('用户中心');
  });

  it('should_return_null_when_only_button_roots_present', () => {
    // 根级仅剩 BUTTON 叶子（无 path）→ 过滤后为空 → 回退静态 NAV 的 null 约定
    expect(buildDynamicNav([btnMenuNode({ parentId: 0, menuName: '孤立按钮' })], [])).toBeNull();
  });
});

// ————— App shell 布局：整页视口定高、内容区内部滚动（复用整棵 App 渲染 harness） —————
describe('App shell 布局：内容区视口内自滚', () => {
  it('should_scroll_content_inside_viewport_when_desktop_layout', async () => {
    await renderApp(HELD_PERMS, MENUS);
    await waitFor(() => {
      expect(menuItemCount()).toBeGreaterThanOrEqual(2);
    });

    const content = document.querySelector('main.ant-layout-content') as HTMLElement;
    expect(content).toBeTruthy();
    expect(content.style.overflow).toBe('auto');
    expect(content.style.minHeight).toBe('0');
    const column = content.closest('.ant-layout') as HTMLElement;
    expect(column.style.height).toBe('100%');
    expect(column.style.overflow).toBe('hidden');
    const shell = column.parentElement as HTMLElement;
    expect(shell.style.height).toBe('100vh');
    expect(shell.style.overflow).toBe('hidden');
    const header = column.querySelector('header.ant-layout-header') as HTMLElement;
    expect(header.style.flexShrink).toBe('0');
  });

  // 侧栏菜单可滚（菜单溢出修复）：目录展开后菜单高度超视口时，菜单自身纵向滚动（flex 列内
  // 滚动区），而不是溢出 Sider 被 Layout overflow:hidden 直接裁掉（jsdom 无布局，按外壳
  // 布局用例同口径断言滚动区必备的行内样式契约）
  it('should_scroll_sider_menu_inside_viewport_when_menu_taller_than_sider', async () => {
    await renderApp(HELD_PERMS, MENUS);
    await waitFor(() => {
      expect(menuItemCount()).toBeGreaterThanOrEqual(2);
    });

    const menu = document.querySelector('.ant-layout-sider [role="menu"]') as HTMLElement | null;
    expect(menu).toBeTruthy();
    expect(menu!.style.overflowY).toBe('auto');
    expect(menu!.style.overflowX).toBe('hidden');
    expect(menu!.style.minHeight).toBe('0'); // 解除 flex 最小高度约束 → 菜单可矮于自身内容
    expect(menu!.style.flexGrow).toBe('1'); // 占满 brand 与底栏之间余量，剩余空间才是滚动视口
  });
});

// ————— 顶栏语言切换 + 设置入口（语言与主题/锁定同栏；设置不再依赖动态菜单收录） —————
describe('顶栏语言切换与设置入口', () => {
  beforeEach(async () => {
    // 基线钉死 zh-CN（jsdom navigator.language=en-US，防初始语言漂移）
    await act(async () => {
      await i18n.changeLanguage('zh-CN');
    });
    window.localStorage.clear();
  });

  afterEach(async () => {
    await act(async () => {
      await i18n.changeLanguage('zh-CN');
    });
    useTimezoneStore.getState().setTimezone('system');
  });

  const openHeaderMenu = async (triggerName: string, itemText: string) => {
    fireEvent.mouseEnter(screen.getByRole('button', { name: triggerName }));
    // 全套件并行时 hover 弹层渲染可能超过默认 1s 查找窗口，放宽到 5s
    fireEvent.click(await screen.findByText(itemText, {}, { timeout: 5000 }));
  };

  it('should_switch_locale_and_date_format_when_header_language_used', async () => {
    await renderApp(HELD_PERMS, MENUS);
    await screen.findByText('首页概览');

    await openHeaderMenu('切换语言', 'English');
    await waitFor(() => {
      expect(useLocaleStore.getState().locale).toBe('en-US');
    }, { timeout: 5000 });
    expect(getDateLocale()).toBe('en-US'); // 日期 formatter 联动
    expect(screen.getByRole('button', { name: 'Settings' })).toBeTruthy(); // 顶栏文案随语言
    expect(Array.from({ length: window.localStorage.length }, (_, i) => window.localStorage.getItem(window.localStorage.key(i) ?? ''))
      .some((v) => v?.includes('en-US'))).toBe(true); // locale 持久化

    await openHeaderMenu('Switch language', '简体中文');
    await waitFor(() => {
      expect(useLocaleStore.getState().locale).toBe('zh-CN');
    }, { timeout: 5000 });
    expect(getDateLocale()).toBe('zh-CN');
  }, 30000); // 两次切换各含 5s 查找窗口，全套件并行负载下放宽单例时限

  it('should_navigate_to_settings_when_header_settings_clicked', async () => {
    await renderApp(HELD_PERMS, MENUS);
    await screen.findByText('首页概览');

    fireEvent.click(screen.getByRole('button', { name: '设置' }));
    await waitFor(() => {
      expect(window.location.hash).toBe('#/settings');
    }, { timeout: 5000 });
    expect(await screen.findByText('外观', {}, { timeout: 5000 })).toBeTruthy(); // 设置页外观卡片挂载
  });

  it('should_not_offer_locale_submenu_in_user_menu_when_language_in_header', async () => {
    await renderApp(HELD_PERMS, MENUS);
    await screen.findByText('首页概览');

    fireEvent.mouseEnter(screen.getByRole('button', { name: '打开用户菜单' }));
    await screen.findByText('退出登录', {}, { timeout: 5000 });
    expect(screen.queryByText('语言 / Language')).toBeNull();
    expect(screen.queryByText('简体中文')).toBeNull();
  });

  it('should_switch_timezone_when_header_timezone_used', async () => {
    await renderApp(HELD_PERMS, MENUS);
    await screen.findByText('首页概览');

    await openHeaderMenu('切换时区', 'UTC');
    await waitFor(() => {
      expect(useTimezoneStore.getState().timezone).toBe('UTC');
    }, { timeout: 5000 });
    expect(window.localStorage.getItem('acme.timezone.v1')).toContain('UTC');
    expect(screen.getByRole('button', { name: '切换时区' }).textContent).toContain('UTC+0'); // 顶栏短标签跟随

    await openHeaderMenu('切换时区', '跟随系统');
    await waitFor(() => {
      expect(useTimezoneStore.getState().timezone).toBe('system');
    }, { timeout: 5000 });
  }, 30000);
});
