import { lazy, type ComponentType, type ReactElement } from 'react';

/**
 * 页面路由常量（REQ-20261119-063129 契约①④）：App.tsx 的 Route 定义提取为唯一页面清单，
 * router（HashRouter）仅承担「导航指令」，渲染由 TabKeepAlive 按 tabsStore 已开页签驱动。
 *
 * - element 全部 lazy 化：每页独立 chunk，页签首次进入才加载（Suspense fallback 在
 *   TabKeepAlive 容器内）；login 单列（App 条件渲染，不进页签），`*` 通配在 App 的
 *   location 同步 effect 里导航 '/'，均不进本清单。
 * - PermissionGate/PageBoundary 逐 tab 包裹由 TabKeepAlive 承担，此处只存裸 element +
 *   permission 元数据（/system/config 既有口径不设门禁，页面内按 system:config:update 分控）。
 * - labelKey 供页签 label 解析（nav.items.*）与命令面板「页面」节使用。
 */

type PageComponent = ComponentType;

export interface PageRoute {
  path: string;
  /** 页签/面板 label 的 i18n 键（nav.items.*）。 */
  labelKey: string;
  /** PermissionGate 权限码（undefined=页面不设门禁）。 */
  permission?: string;
  element: ReactElement;
}

const Dashboard = lazy(() => import('./pages/Dashboard').then((m) => ({ default: m.Dashboard as PageComponent })));
const Releases = lazy(() => import('./pages/Releases').then((m) => ({ default: m.Releases as PageComponent })));
const Apps = lazy(() => import('./pages/Apps').then((m) => ({ default: m.Apps as PageComponent })));
const TenantsPage = lazy(() => import('./pages/TenantsPage').then((m) => ({ default: m.TenantsPage as PageComponent })));
const UsersPage = lazy(() => import('./pages/UsersPage').then((m) => ({ default: m.UsersPage as PageComponent })));
const RolesPage = lazy(() => import('./pages/RolesPage').then((m) => ({ default: m.RolesPage as PageComponent })));
const DeptsPage = lazy(() => import('./pages/DeptsPage').then((m) => ({ default: m.DeptsPage as PageComponent })));
const PositionsPage = lazy(() => import('./pages/PositionsPage').then((m) => ({ default: m.PositionsPage as PageComponent })));
const PermissionsPage = lazy(() => import('./pages/PermissionsPage').then((m) => ({ default: m.PermissionsPage as PageComponent })));
const MenusPage = lazy(() => import('./pages/MenusPage').then((m) => ({ default: m.MenusPage as PageComponent })));
const DictsPage = lazy(() => import('./pages/DictsPage').then((m) => ({ default: m.DictsPage as PageComponent })));
const SystemConfigPage = lazy(() => import('./pages/SystemConfigPage').then((m) => ({ default: m.SystemConfigPage as PageComponent })));
const NoticesPage = lazy(() => import('./pages/NoticesPage').then((m) => ({ default: m.NoticesPage as PageComponent })));
const AuditPage = lazy(() => import('./pages/AuditPage').then((m) => ({ default: m.AuditPage as PageComponent })));
const LoginLogsPage = lazy(() => import('./pages/LoginLogsPage').then((m) => ({ default: m.LoginLogsPage as PageComponent })));
const OnlineUsersPage = lazy(() => import('./pages/OnlineUsersPage').then((m) => ({ default: m.OnlineUsersPage as PageComponent })));
const FilesPage = lazy(() => import('./pages/FilesPage').then((m) => ({ default: m.FilesPage as PageComponent })));
const ExceptionsPage = lazy(() => import('./pages/ExceptionsPage').then((m) => ({ default: m.ExceptionsPage as PageComponent })));
const JobsPage = lazy(() => import('./pages/JobsPage').then((m) => ({ default: m.JobsPage as PageComponent })));
const Settings = lazy(() => import('./pages/Settings').then((m) => ({ default: m.Settings as PageComponent })));

/** 20 页清单（21 条 Route − login − `*`；/files 为 REQ-20261119-063129 契约⑥补齐）。 */
export const PAGE_ROUTES: PageRoute[] = [
  { path: '/', labelKey: 'nav.items.dashboard', element: <Dashboard /> },
  { path: '/releases', labelKey: 'nav.items.releases', element: <Releases /> },
  { path: '/apps', labelKey: 'nav.items.apps', element: <Apps /> },
  { path: '/tenants', labelKey: 'nav.items.tenants', permission: 'system:tenant:list', element: <TenantsPage /> },
  { path: '/users', labelKey: 'nav.items.users', permission: 'system:user:list', element: <UsersPage /> },
  { path: '/roles', labelKey: 'nav.items.roles', permission: 'system:role:list', element: <RolesPage /> },
  { path: '/depts', labelKey: 'nav.items.depts', permission: 'system:dept:list', element: <DeptsPage /> },
  { path: '/positions', labelKey: 'nav.items.positions', permission: 'system:position:list', element: <PositionsPage /> },
  { path: '/permissions', labelKey: 'nav.items.permissions', permission: 'system:permission:list', element: <PermissionsPage /> },
  { path: '/menus', labelKey: 'nav.items.menus', permission: 'system:menu:list', element: <MenusPage /> },
  { path: '/dicts', labelKey: 'nav.items.dicts', permission: 'system:dict:list', element: <DictsPage /> },
  { path: '/notices', labelKey: 'nav.items.notices', permission: 'system:notice:list', element: <NoticesPage /> },
  { path: '/audit', labelKey: 'nav.items.audit', permission: 'infra:audit:list', element: <AuditPage /> },
  { path: '/login-logs', labelKey: 'nav.items.loginLogs', permission: 'auth:loginlog:list', element: <LoginLogsPage /> },
  { path: '/online-users', labelKey: 'nav.items.onlineUsers', permission: 'auth:online:list', element: <OnlineUsersPage /> },
  { path: '/files', labelKey: 'nav.items.files', permission: 'infra:file:list', element: <FilesPage /> },
  // 运行异常日志（REQ-20261117-061017）：权限点 infra:exception:list
  { path: '/op-exceptions', labelKey: 'nav.items.opExceptions', permission: 'infra:exception:list', element: <ExceptionsPage /> },
  // 定时任务（REQ-20261117-061017）：权限点 infra:job:list（手动执行 infra:job:run 页内分控）
  { path: '/jobs', labelKey: 'nav.items.jobs', permission: 'infra:job:list', element: <JobsPage /> },
  // 系统配置（REQ-20261116-230354）：GET 认证即可读，页面不设 PermissionGate；
  // 保存能力在页面内按 system:config:update fail-closed（AC-6）
  { path: '/system/config', labelKey: 'nav.items.systemConfig', element: <SystemConfigPage /> },
  { path: '/settings', labelKey: 'nav.items.settings', element: <Settings /> },
];

/** path → PageRoute 线性查找（页签数 ≤ 20，无需 Map）。 */
export function findPageRoute(path: string): PageRoute | undefined {
  return PAGE_ROUTES.find((route) => route.path === path);
}
