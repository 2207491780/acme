import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, App as AntApp, Badge, Button, Drawer, Dropdown, Grid, Input, Layout, Menu, Space, Watermark } from 'antd';
import type { InputRef, MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  ApartmentOutlined,
  AppstoreOutlined,
  BookOutlined,
  BugOutlined,
  BgColorsOutlined,
  ControlOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  DownOutlined,
  FieldTimeOutlined,
  FileOutlined,
  FileSearchOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  GiftOutlined,
  GlobalOutlined,
  HistoryOutlined,
  IdcardOutlined,
  LockOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuOutlined,
  MoonOutlined,
  NotificationOutlined,
  PartitionOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
  SearchOutlined,
  SettingOutlined,
  SunOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ACTIVE_STATUSES, ApiError, authApi, effectiveTimezone, renderWatermarkTemplate, resolveAccentColor, resolveSemanticColors, resolveThemeMode, startAuthRenewal, SUPPORTED_LOCALES, SUPPORTED_TIMEZONES, useAuthStore, useLocaleStore, useLockStore, useMeStore, useNotifyStore, useSystemConfigStore, useTimezoneStore, type AppLocale, type TenantSummary, type TimezonePref } from '@acme/core';
import dayjs from 'dayjs';
import { PAGE_ROUTES, findPageRoute, tabPathOf } from './routes';
import { Avatar } from './components/Avatar';
import { useTabsStore } from './store/tabsStore';
import { useStore } from './store/useStore';
import { detectPlatform, download } from './utils/dom';
import { createNotifyClient } from './ws/notify-client';
import { LoginPage } from './pages/LoginPage';
import { NotificationBell } from './components/NotificationBell';
import { PageBoundary, PermissionGate } from './components/PermissionGate';
import { TabBar } from './components/TabBar';
import { TabKeepAlive } from './components/TabKeepAlive';
import { DateTimePrefsRefresh } from './components/DateTimePrefsRefresh';
import { CommandPalette } from './components/CommandPalette';
import { PreferencesDrawer } from './components/PreferencesDrawer';
import { useSystemPrefersDark } from './hooks/useSystemPrefersDark';
import { usePreferencesSync } from './hooks/usePreferencesSync';
import { buildDynamicNav, type NavEntry } from './nav';
import { ReleaseDrawer } from './components/ReleaseDrawer';
import { ReleaseFormModal } from './components/ReleaseFormModal';
import { SaveViewModal } from './components/SaveViewModal';
import { AppFormModal } from './components/AppFormModal';
import { LockScreenOverlay } from './components/LockScreenOverlay';
import { useFullscreen } from './hooks/useFullscreen';

const { Sider, Header, Content } = Layout;

const NAV: { path: string; icon: ReactNode; labelKey: string; permission?: string }[] = [
  { path: '/', icon: <DashboardOutlined />, labelKey: 'nav.items.dashboard' },
  { path: '/releases', icon: <UnorderedListOutlined />, labelKey: 'nav.items.releases' },
  { path: '/apps', icon: <AppstoreOutlined />, labelKey: 'nav.items.apps' },
  { path: '/tenants', icon: <ApartmentOutlined />, labelKey: 'nav.items.tenants', permission: 'system:tenant:list' },
  // 套餐管理（REQ-20261121-022910）：me.menus 空时的静态导航回退条目（动态导航由菜单种子 V24 下发）
  { path: '/tenant-packages', icon: <GiftOutlined />, labelKey: 'nav.items.tenantPackages', permission: 'system:tenant-package:list' },
  { path: '/users', icon: <UserOutlined />, labelKey: 'nav.items.users', permission: 'system:user:list' },
  { path: '/roles', icon: <TeamOutlined />, labelKey: 'nav.items.roles', permission: 'system:role:list' },
  { path: '/depts', icon: <PartitionOutlined />, labelKey: 'nav.items.depts', permission: 'system:dept:list' },
  { path: '/positions', icon: <IdcardOutlined />, labelKey: 'nav.items.positions', permission: 'system:position:list' },
  { path: '/permissions', icon: <SafetyCertificateOutlined />, labelKey: 'nav.items.permissions', permission: 'system:permission:list' },
  { path: '/menus', icon: <MenuOutlined />, labelKey: 'nav.items.menus', permission: 'system:menu:list' },
  { path: '/dicts', icon: <BookOutlined />, labelKey: 'nav.items.dicts', permission: 'system:dict:list' },
  { path: '/system/config', icon: <ControlOutlined />, labelKey: 'nav.items.systemConfig', permission: 'system:config:list' },
  { path: '/notices', icon: <NotificationOutlined />, labelKey: 'nav.items.notices', permission: 'system:notice:list' },
  { path: '/audit', icon: <FileSearchOutlined />, labelKey: 'nav.items.audit', permission: 'infra:audit:list' },
  { path: '/login-logs', icon: <HistoryOutlined />, labelKey: 'nav.items.loginLogs', permission: 'auth:loginlog:list' },
  { path: '/online-users', icon: <TeamOutlined />, labelKey: 'nav.items.onlineUsers', permission: 'auth:online:list' },
  // 平台运营组（REQ-20261117-061017）：异常日志 / 定时任务，紧随审计与登录安全观测项之后
  { path: '/op-exceptions', icon: <BugOutlined />, labelKey: 'nav.items.opExceptions', permission: 'infra:exception:list' },
  // 调度中心（REQ-20261122-023213 JOB-3）：复用 /jobs，权限点换 scheduler:job:list（73；菜单种子 V28 同步）
  { path: '/jobs', icon: <ScheduleOutlined />, labelKey: 'nav.items.jobs', permission: 'scheduler:job:list' },
  // 文件管理（REQ-20261119-063129 契约⑥）：路由既有，NAV/i18n 条目补齐
  { path: '/files', icon: <FileOutlined />, labelKey: 'nav.items.files', permission: 'infra:file:list' },
  // BPM 流程设计器（REQ-20261122-023441 确认④）：/workflow 条目（labelKey=nav.items.workflowDesigner 域）
  { path: '/workflow', icon: <DeploymentUnitOutlined />, labelKey: 'nav.items.workflowDesigner', permission: 'workflow:definition:list' },
  { path: '/settings', icon: <SettingOutlined />, labelKey: 'nav.items.settings' },
  // 个人中心（REQ-20261119-082531 契约⑦）：认证即可；icon 取 id-card 类标识（与注册表 positions 同族）
  { path: '/profile', icon: <IdcardOutlined />, labelKey: 'nav.items.profile' },
];

/** 静态 NAV 图标映射（页签条图标兜底；动态菜单 icon 优先）。 */
const NAV_ICONS = new Map<string, ReactNode>(NAV.map((entry) => [entry.path, entry.icon]));

/** 顶栏语言菜单展示名（复用 i18n 资源包 nav.userMenu.language*，语言名以自身语言展示）。 */
const LOCALE_LABEL_KEYS: Record<AppLocale, string> = {
  'zh-CN': 'nav.userMenu.languageZh',
  'en-US': 'nav.userMenu.languageEn',
};

export function App() {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const location = useLocation();
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.lg;

  const settings = useStore((s) => s.state.settings);
  const releases = useStore((s) => s.state.releases);
  const query = useStore((s) => s.query);
  const setQuery = useStore((s) => s.setQuery);
  const dispatch = useStore((s) => s.dispatch);
  const openModal = useStore((s) => s.openModal);
  const storageError = useStore((s) => s.storageError);
  const storageConflict = useStore((s) => s.storageConflict);

  const [collapsed, setCollapsed] = useState(false);
  const [mobileNav, setMobileNav] = useState(false);
  // 命令面板（REQ-20261119-063129 契约⑤）：Ctrl+K 开关（动作经 props 注入，见 CommandPalette）
  const [paletteOpen, setPaletteOpen] = useState(false);
  // 偏好抽屉（REQ-20261121-010725）：顶栏入口开关（抽屉本体见 PreferencesDrawer）
  const [prefsOpen, setPrefsOpen] = useState(false);
  // 超管跨租户切换（REQ-20261117-025200）：目标租户目录（用户菜单展开时拉取）+ 切换中防重复点击
  const [switchTargets, setSwitchTargets] = useState<TenantSummary[]>([]);
  const [switchTargetsLoading, setSwitchTargetsLoading] = useState(false);
  const [switchingTenant, setSwitchingTenant] = useState(false);
  const searchRef = useRef<InputRef>(null);
  // 页签滚动位置（契约 AC-1）：Content 为共享滚动视口，切签时按 path 记忆/恢复 scrollTop
  const contentRef = useRef<HTMLElement | null>(null);
  const scrollTopsRef = useRef(new Map<string, number>());

  // 多页签逃生口（契约⑦）：置 true 时退回单页路由渲染（无 TabBar/keepalive），仅测试装配使用
  const tabsDisabled = Boolean(window.__ACME_TABS_DISABLED__);

  // 登录态（login-state / login-renew）：未登录渲染独立登录页；已登录进入控制台并启动滑动续约
  const session = useAuthStore((s) => s.session);
  const isAuthed = session !== null;
  useEffect(() => startAuthRenewal(), []);

  // 偏好后端持久化（REQ-20261121-200415 契约⑥）：登录拉取后端权威覆盖本地 + 偏好变更防抖
  // 800ms PUT themePreference + 401/未登录静默停上行。hooks 必须位于下方条件 return 之前
  // （与下方 P0 注释同口径：登录/登出切换时 hook 数量不得变化）。
  usePreferencesSync();

  // 会话恢复校验（p1-me-sync）：进入应用即拉取服务端画像（me 401 由 client 续约/清会话处理）。
  // 依赖收敛为 isAuthed：滑动续约会整体替换 session 对象，以 session 为依赖会导致每次续约重复拉取 me。
  // 租户系统配置（REQ-20261116-230354）随会话就绪加载（水印显隐 + 闲置锁屏时长 + 页脚版权强制生效）；
  // 登出/会话失效复位为缺省（水印开/验证码关/闲置关/页脚不显示）并解除闲置计时。
  // 页签（REQ-20261119-063129 AC-7＋迭代轮次 1 契约⑩）：登入 hydrate（新会话=空/刷新=还原持久化）
  // 后种子首页常驻签；登出/被踢 clear（移除存储键）后重建 dashboard permanent（不入盘，AC-7 不变）。
  useEffect(() => {
    if (isAuthed) {
      useTabsStore.getState().hydrate();
      useTabsStore.getState().seedDashboardPermanent();
      void useMeStore.getState().load();
      void useSystemConfigStore.getState().load();
    } else {
      useTabsStore.getState().clear();
      useTabsStore.getState().seedDashboardPermanent();
      useMeStore.getState().clear();
      useSystemConfigStore.setState({
        watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
        footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0', loaded: false,
      });
      useLockStore.getState().setIdleTimeoutMs(0);
    }
  }, [isAuthed]);

  // 站内信实时链路（REQ-20261114-120102，AC-10）：登录态挂载即建连，message:new 帧 → 通知 store
  // （铃铛徽标实时 +1）；登出/卸载主动关闭不重连。令牌随 connect 时从 useAuthStore 会话取。
  useEffect(() => {
    if (!isAuthed) {
      return undefined;
    }
    const client = createNotifyClient();
    client.on('message:new', (frame) => useNotifyStore.getState().applyMessageNew(frame));
    client.connect();
    return () => client.close();
  }, [isAuthed]);

  // 主题/时钟/快捷键副作用必须无条件注册：hooks 不得位于下方条件 return（if (!session)）之后，
  // 否则登录/登出切换时 hook 数量变化将触发 React "Rendered more/fewer hooks" 崩溃（review 修复 P0）。
  // 生效模式 = preferences.mode 经 resolveThemeMode 解析（system → matchMedia 注入值，AC-4）
  const systemPrefersDark = useSystemPrefersDark();
  const preferences = settings.preferences;
  const resolvedMode = resolveThemeMode(preferences.mode, systemPrefersDark);
  const accentHex = resolveAccentColor(preferences);
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = resolvedMode;
    root.dataset.accent = preferences.accent;
    root.dataset.density = preferences.density;
    root.dataset.motion = String(preferences.motionEnabled);
    // 自定义取色（REQ-20261121-010725 AC-7）：inline 变量覆盖 global.css 的 [data-accent] 镜像；
    // 清除后回落预设选择。预设键仍写入 data-accent（splash/校验页按预设口径渲染）。
    if (preferences.customAccent) {
      root.style.setProperty('--accent', preferences.customAccent);
    } else {
      root.style.removeProperty('--accent');
    }
    // 语义色覆盖（REQ-20261121-043502 确认⑤）：global.css 已有 TONES 镜像变量（--teal=success、
    // --red=danger）随覆盖 inline 联动，清除回基线；warning 无专属镜像变量、info/muted 不覆盖。
    const semantic = resolveSemanticColors(preferences.semanticColors);
    if (preferences.semanticColors?.success) {
      root.style.setProperty('--teal', semantic.success);
    } else {
      root.style.removeProperty('--teal');
    }
    if (preferences.semanticColors?.danger) {
      root.style.setProperty('--red', semantic.danger);
    } else {
      root.style.removeProperty('--red');
    }
    // 灰度/色弱（REQ-20261121-043502 确认④）：data-surface 挂根（值序 gray→weak 与 global.css
    // 叠加规则一致）；全关移除属性避免常驻 filter 上下文。boot 首绘不接线（确认④取舍）。
    const surface: string[] = [];
    if (preferences.grayMode) surface.push('gray');
    if (preferences.weakMode) surface.push('weak');
    if (surface.length > 0) {
      root.dataset.surface = surface.join(' ');
    } else {
      delete root.dataset.surface;
    }
  }, [resolvedMode, preferences]);

  useEffect(() => {
    const timer = setInterval(() => dispatch({ type: 'TICK' }), 1200);
    return () => clearInterval(timer);
  }, [dispatch]);

  // Ctrl+K（REQ-20261119-063129 契约⑤）：改开命令面板（登录态）；既有搜索框保留原语义
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        if (useAuthStore.getState().session !== null) {
          setPaletteOpen(true);
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // 全屏 / 水印（REQ-20261116-230354，AC-7）：显隐由租户系统配置强制决定（个人设置项已移除）
  const { isFullscreen, enter: enterFullscreen, exit: exitFullscreen } = useFullscreen();
  const watermarkEnabled = useSystemConfigStore((s) => s.watermarkEnabled);
  // 水印模板（REQ-20261117-180000）：null/空=仅登录账号（既有口径）；含 {date}/{time} 时每分钟刷新渲染
  const watermarkTemplate = useSystemConfigStore((s) => s.watermarkTemplate);
  const watermarkNeedsClock = /\{(date|time)\}/.test(watermarkTemplate ?? '');
  const [watermarkNow, setWatermarkNow] = useState(() => new Date());
  useEffect(() => {
    setWatermarkNow(new Date());
    if (!watermarkEnabled || !watermarkNeedsClock) {
      return undefined;
    }
    const timer = setInterval(() => setWatermarkNow(new Date()), 60_000);
    return () => clearInterval(timer);
  }, [watermarkEnabled, watermarkNeedsClock, watermarkTemplate]);
  // 应用内页脚版权（REQ-20261117-025200，AC-10）：显隐由租户配置强制决定（visible 且文案非空才渲染）
  const footerVisible = useSystemConfigStore((s) => s.footerVisible);
  const footerCopyright = useSystemConfigStore((s) => s.footerCopyright);

  // 闲置锁屏（AC-8）：时长由租户配置经 systemConfigStore 副作用武装（setIdleTimeoutMs）；
  // 此处仅注册用户活动打点（重置计时）与卸载清理
  useEffect(() => {
    const onTouch = () => useLockStore.getState().touch();
    document.addEventListener('pointerdown', onTouch);
    document.addEventListener('keydown', onTouch);
    return () => {
      document.removeEventListener('pointerdown', onTouch);
      document.removeEventListener('keydown', onTouch);
      useLockStore.getState().stopIdleWatch();
    };
  }, []);

  const handleLogout = () => {
    void authApi
      .logout()
      .catch(() => undefined) // 登出幂等：令牌已失效也清空本地会话
      .finally(() => {
        useAuthStore.getState().clear();
        useMeStore.getState().clear();
        useTabsStore.getState().clear(); // AC-7：登出清空页签并移除 acme.tabs.v1
        window.location.hash = '#/login';
      });
  };

  const me = useMeStore((s) => s.me);
  const displayName = me?.user.nickname || me?.user.username || session?.nickname || session?.username || '';
  const rolesText = me?.roles.join(t('nav.userMenu.rolesSeparator')) || t('nav.userMenu.rolesLoading');
  // 超管跨租户切换入口（REQ-20261117-025200）：仅 super_admin 可见（me.roles 前端判断 + 后端 403 兜底）
  const isSuperAdmin = me?.roles.includes('super_admin') ?? false;
  // 目标租户目录 = 登录下拉同源 listEnabled 公开端点（只含 ENABLED 且未过期）；此处仅过滤当前租户自身。
  // 目录加载失败 → 子菜单空态（非关键面，不阻塞用户菜单其余项）。
  const loadSwitchTargets = () => {
    if (switchTargetsLoading) {
      return;
    }
    setSwitchTargetsLoading(true);
    authApi.tenants()
      .then((list) => {
        const safeList = Array.isArray(list) ? list : [];
        setSwitchTargets(safeList.filter((item) => item.tenantCode !== session?.tenantCode));
      })
      .catch(() => setSwitchTargets([]))
      .finally(() => setSwitchTargetsLoading(false));
  };
  const tenantSwitchGroup: MenuProps['items'] = [
    {
      key: 'tenant-switch',
      icon: <ApartmentOutlined />,
      label: t('nav.userMenu.tenantSwitch'),
      disabled: switchingTenant,
      children: switchTargetsLoading && switchTargets.length === 0
        ? [{ key: 'tenant-switch-loading', label: t('nav.userMenu.tenantSwitchLoading'), disabled: true }]
        : switchTargets.map((item) => ({
          key: `tenant-switch-${item.tenantCode}`,
          label: `${item.tenantName}（${item.tenantCode}）`,
          disabled: switchingTenant,
        })),
    },
  ];
  const userMenuItems: MenuProps['items'] = [
    { key: 'who', label: t('nav.userMenu.tenantLabel', { name: displayName, tenant: session?.tenantCode ?? '' }), disabled: true },
    {
      key: 'roles',
      label: t('nav.userMenu.rolesLabel', { roles: rolesText }),
      disabled: true,
    },
    ...(isSuperAdmin ? tenantSwitchGroup : []),
    { type: 'divider' },
    // 个人中心（REQ-20261119-082531 契约⑦）：用户菜单直达 /profile
    { key: 'profile', icon: <IdcardOutlined />, label: t('nav.userMenu.profile') },
    { key: 'logout', icon: <LogoutOutlined />, label: t('nav.userMenu.logout'), danger: true },
  ];

  // —— 导航动态化（REQ-20261114-120101，AC-7）：登录后按 me.menus 菜单树渲染（含权限码过滤）；
  // menus 缺失/为空/过滤后无可见项 → 回退静态 NAV（fail-closed 不变）；仅控显隐，不做动态路由注册。
  // 注意：本段含 hooks（useLocaleStore/useMemo/useState/useEffect），必须位于下方条件 return
  // （if (!session)）之前——登录/登出（含 401 清会话）切换时 hook 数量不得变化，否则触发
  // React "Rendered fewer hooks than expected" 崩溃（同上方 P0 注释口径）。
  const activeCount = releases.filter((r) => ACTIVE_STATUSES.includes(r.status)).length;
  const labelOf = (n: NavEntry) =>
    n.path === '/releases' ? (
      <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        {n.label}
        <Badge count={activeCount} size="small" color="var(--accent)" />
      </span>
    ) : (
      n.label
    );
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const timezone = useTimezoneStore((s) => s.timezone);
  const setTimezone = useTimezoneStore((s) => s.setTimezone);
  const dynamicNav = useMemo(
    () => (me && (me.menus?.length ?? 0) > 0 ? buildDynamicNav(me.menus, me.permissions, (_label, entry) => labelOf(entry)) : null),
    // labelOf 依赖 activeCount（发布单活跃数）与 t（语言切换后菜单文案重译），随渲染更新
    [me, activeCount, t],
  );
  // 动态菜单的目录分组默认展开（受控）：保证分组子项在侧边导航直达可见
  const [navOpenKeys, setNavOpenKeys] = useState<string[] | undefined>(undefined);
  useEffect(() => {
    if (dynamicNav) {
      setNavOpenKeys(dynamicNav.openKeys);
    }
  }, [dynamicNav]);

  // 页签同步（REQ-20261119-063129 契约①②）：location.pathname → tabsStore（渲染与 router 脱钩，
  // navigate 仅承担「导航指令」）。/login 不进页签；未知路径（`*` 通配语义）replace 导航 '/'；
  // 页签 label 打开时优先记动态菜单 menuName，否则由 labelKey 渲染时解析（TabBar）。
  // 注意：本 effect 必须位于上方 hydrate effect 之后（同序执行，先还原再开当前签），
  // 且在条件 return 之前——登录/登出切换时 hook 数量不得变化。
  useEffect(() => {
    if (tabsDisabled) {
      return;
    }
    if (useAuthStore.getState().session === null) {
      return;
    }
    const path = location.pathname;
    if (path === '/login') {
      return;
    }
    // base-path 单实例页签（REQ-20261122-023441 确认④）：/workflow/designer/:id 归一 base path
    const tabPath = tabPathOf(path);
    if (!findPageRoute(tabPath)) {
      navigate('/', { replace: true });
      return;
    }
    const dynamicLabel = dynamicNav?.entries.find((entry) => entry.path === tabPath)?.label
      ?? dynamicNav?.entries.find((entry) => entry.path === path)?.label;
    useTabsStore.getState().openTab(tabPath, dynamicLabel ? { label: dynamicLabel } : undefined);
  }, [location.pathname, dynamicNav, navigate, tabsDisabled]);

  // 页签切换滚动位置恢复（AC-1）：Content 为共享滚动视口（既有布局契约 overflow:auto 不变），
  // 切签时记录旧激活签 scrollTop、恢复新激活签记忆值（keepalive 容器 display:none 不保留共享视口滚动）
  const activeTabPath = useTabsStore((s) => s.activePath);
  const prevTabRef = useRef<string | null>(null);
  useEffect(() => {
    const el = contentRef.current;
    if (!el) {
      return;
    }
    const prev = prevTabRef.current;
    if (prev && prev !== activeTabPath) {
      scrollTopsRef.current.set(prev, el.scrollTop);
    }
    prevTabRef.current = activeTabPath;
    const target = activeTabPath ? (scrollTopsRef.current.get(activeTabPath) ?? 0) : 0;
    const raf = requestAnimationFrame(() => {
      el.scrollTop = target;
    });
    return () => cancelAnimationFrame(raf);
  }, [activeTabPath]);

  if (!session) {
    return <LoginPage />;
  }

  const current = location.pathname;
  const platform = detectPlatform();
  const runtimeLabel = platform === 'desktop'
    ? t('nav.header.runtimeDesktop')
    : platform === 'mobile'
      ? t('nav.header.runtimeMobile')
      : t('nav.header.runtimeWeb');

  const go = (path: string) => {
    navigate(path);
    setMobileNav(false);
  };

  // 顶栏主题切换（REQ-20261121-010725）：按当前生效模式取反写回 mode——
  // 手动选择 light/dark 即脱离 system（契约交互规格）
  const toggleThemeMode = () => {
    dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...preferences, mode: resolvedMode === 'dark' ? 'light' : 'dark' } },
    });
  };

  // 页签条动作（REQ-20261119-063129 契约③）：store 变更后对齐 hash（关激活页切相邻）/
  // 兜底空态（全部关闭且无 pinned 时按当前路由重开，内容区不留白）
  const reconcileTabs = () => {
    const { tabs: openTabs, activePath } = useTabsStore.getState();
    // base-path 单实例页签（REQ-20261122-023441 确认④）：比较与回退均按归一 tab path，
    // 防止 designer 带参路由（/workflow/designer/:id）被重置回 base path
    const tabPath = tabPathOf(location.pathname);
    if (openTabs.length === 0) {
      if (findPageRoute(tabPath)) {
        useTabsStore.getState().openTab(tabPath);
      }
      return;
    }
    if (activePath && activePath !== tabPath) {
      navigate(activePath);
    }
  };
  const handleTabSelect = (path: string) => {
    if (path !== location.pathname) {
      navigate(path);
      return;
    }
    useTabsStore.getState().setActive(path);
  };
  const handleTabClose = (path: string) => {
    useTabsStore.getState().closeTab(path);
    reconcileTabs();
  };
  const handleTabRefresh = (path: string) => {
    useTabsStore.getState().refresh(path);
  };
  const handleTabTogglePin = (path: string) => {
    useTabsStore.getState().togglePin(path);
  };
  const handleTabCloseOthers = (path: string) => {
    useTabsStore.getState().closeOthers(path);
    reconcileTabs();
  };
  const handleTabCloseRight = (path: string) => {
    useTabsStore.getState().closeRight(path);
    reconcileTabs();
  };
  const handleTabCloseAll = () => {
    useTabsStore.getState().closeAll();
    reconcileTabs();
  };
  // 页签图标：动态菜单 icon 优先，静态 NAV 兜底（icon 不可序列化，渲染时注入 TabBar）；
  // designer 带参路由按归一 tab path 回退（NAV 条目为 /workflow）
  const tabIconOf = (path: string): ReactNode =>
    dynamicNav?.entries.find((entry) => entry.path === path)?.icon
    ?? NAV_ICONS.get(path)
    ?? NAV_ICONS.get(tabPathOf(path))
    ?? null;

  const onSearch = (value: string) => {
    setQuery({ search: value });
    if (location.pathname !== '/releases') navigate('/releases');
  };

  const onUserMenuClick: MenuProps['onClick'] = ({ key }) => {
    if (key === 'logout') {
      handleLogout();
      return;
    }
    if (key === 'profile') {
      go('/profile');
      return;
    }
    if (key.startsWith('tenant-switch-')) {
      handleTenantSwitch(key.slice('tenant-switch-'.length));
      return;
    }
    if (key.startsWith('locale-')) {
      setLocale(key.slice('locale-'.length) as AppLocale);
    }
  };

  // 超管跨租户切换（REQ-20261117-025200）：签发目标租户新令牌并整体替换会话（remember 沿用当前值）；
  // 旧会话被互踢属预期（切换即签新会话）；新租户画像与租户级配置随新会话重新加载；成功后回概览。
  const handleTenantSwitch = (targetTenantCode: string) => {
    if (switchingTenant) {
      return;
    }
    setSwitchingTenant(true);
    authApi.tenantSwitch(targetTenantCode)
      .then((issued) => {
        useAuthStore.getState().setSession({
          accessToken: issued.accessToken,
          expiresAtMs: Date.parse(issued.expiresAt),
          userId: issued.userId,
          username: issued.username,
          nickname: issued.nickname,
          tenantCode: targetTenantCode,
        }, useAuthStore.getState().remember);
        void useMeStore.getState().load();
        void useSystemConfigStore.getState().load();
        message.success(t('nav.userMenu.tenantSwitchSuccess', { tenant: targetTenantCode }));
        go('/');
      })
      .catch((ex: unknown) => {
        message.error(ex instanceof ApiError ? ex.message : t('nav.userMenu.tenantSwitchFailed'));
      })
      .finally(() => setSwitchingTenant(false));
  };

  // 导航按权限过滤（p2-admin）：me 未加载时先全部隐藏，加载后按权限码展示（fail-closed）
  const permittedNav = NAV.filter((n) => !n.permission || (me?.permissions.includes(n.permission) ?? false))
    .map((n) => ({ path: n.path, icon: n.icon, label: t(n.labelKey) }));
  // 命令面板「页面」节权限过滤（REQ-20261120-120552 AC-10）：与上方 permittedNav 同源判定
  // （me.permissions；me 未加载 fail-closed，无 permission 码的路由恒可见）
  const hasPermission = (code?: string): boolean => !code || (me?.permissions.includes(code) ?? false);
  // 底栏/点击守卫用的扁平叶子集合（动态取菜单树叶子；回退取静态 NAV）
  const navEntries: NavEntry[] = dynamicNav?.entries ?? permittedNav;
  const leafPaths = new Set(navEntries.map((n) => n.path));
  const menuItems: MenuProps['items'] = dynamicNav?.items ?? permittedNav.map((n) => ({ key: n.path, icon: n.icon, label: labelOf(n) }));

  const renderNav = (expanded: boolean) => (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div className="brand">
        <div className="brand-mark">
          <ThunderboltOutlined style={{ fontSize: 22 }} />
        </div>
        {expanded && (
          <div>
            <div className="brand-title">{t('nav.brand.title')}</div>
            <div className="brand-sub">{t('nav.brand.sub')}</div>
          </div>
        )}
      </div>
      <Menu
        mode="inline"
        selectedKeys={[current]}
        items={menuItems}
        openKeys={navOpenKeys}
        onOpenChange={(keys) => setNavOpenKeys(keys as string[])}
        onClick={({ key }) => go(leafPaths.has(key) ? key : '/')}
        // 侧栏菜单可滚（flex 列内滚动区）：flex:1 占满 brand 与底栏之间余量，minHeight:0 解除
        // flex 子项最小高度约束，菜单高于视口时自身纵向滚动，不再溢出 Sider 被 Layout
        // overflow:hidden 直接裁掉
        style={{ border: 'none', padding: '0 8px', flex: 1, minHeight: 0, overflowX: 'hidden', overflowY: 'auto' }}
      />
      {expanded && (
        <div className="sidebar-foot">
          <strong>{t('nav.brand.footTitle')}</strong>
          {t('nav.brand.footText')}
        </div>
      )}
    </div>
  );

  const shell = (
    <>
      <Layout style={{ height: '100vh', overflow: 'hidden' }}>
        {!isMobile && (
          <Sider
            collapsible
            trigger={null}
            collapsed={collapsed}
            width={248}
            collapsedWidth={72}
            style={{ position: 'sticky', top: 0, height: '100vh' }}
          >
            {renderNav(!collapsed)}
          </Sider>
        )}
        <Layout style={{ height: '100%', minWidth: 0, overflow: 'hidden' }}>
          <Header style={{ display: 'flex', alignItems: 'center', gap: 12, position: 'sticky', top: 0, zIndex: 20, flexShrink: 0 }}>
            <Button
              type="text"
              icon={isMobile || collapsed ? <MenuOutlined /> : <MenuFoldOutlined />}
              onClick={() => (isMobile ? setMobileNav(true) : setCollapsed((c) => !c))}
              aria-label={t('nav.header.toggleNav')}
            />
            <Input
              ref={searchRef}
              allowClear
              prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
              placeholder={t('nav.header.searchPlaceholder')}
              value={query.search}
              onChange={(e) => onSearch(e.target.value)}
              style={{ flex: 1, maxWidth: 420 }}
              suffix={
                !isMobile ? (
                  <kbd
                    style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', border: '1px solid var(--border)', borderRadius: 6, padding: '1px 6px' }}
                    title={t('nav.commandPalette.open')}
                  >
                    Ctrl K
                  </kbd>
                ) : undefined
              }
            />
            <div style={{ flex: 1 }} />
            {!isMobile && (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 8,
                  fontSize: 12,
                  color: 'var(--text-2)',
                  padding: '6px 12px',
                  borderRadius: 999,
                  border: '1px solid var(--border)',
                  background: 'var(--surface)',
                }}
              >
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--acid)' }} />
                {runtimeLabel}
              </span>
            )}
            <NotificationBell />
            <Dropdown
              menu={{
                items: SUPPORTED_LOCALES.map((value) => ({ key: `locale-${value}`, label: t(LOCALE_LABEL_KEYS[value]) })),
                selectedKeys: [`locale-${locale}`],
                onClick: onUserMenuClick,
              }}
            >
              <Button type="text" aria-label={t('nav.header.language')} style={{ paddingInline: 8 }}>
                <Space size={6}>
                  <GlobalOutlined />
                  {!isMobile && <span style={{ fontSize: 12 }}>{locale === 'zh-CN' ? '中' : 'EN'}</span>}
                </Space>
              </Button>
            </Dropdown>
            <Dropdown
              menu={{
                items: SUPPORTED_TIMEZONES.map((value) => ({
                  key: value,
                  label:
                    value === 'system'
                      ? t('settings.timezone.system')
                      : value === 'UTC'
                        ? 'UTC'
                        : `${t(`settings.timezone.zones.${value}`)} (UTC${dayjs().tz(value).format('Z')})`,
                })),
                selectedKeys: [timezone],
                onClick: ({ key }) => setTimezone(key as TimezonePref),
              }}
            >
              <Button type="text" aria-label={t('nav.header.timezone')} style={{ paddingInline: 8 }}>
                <Space size={6}>
                  <FieldTimeOutlined />
                  {!isMobile && (
                    <span style={{ fontSize: 12 }}>
                      UTC{dayjs().tz(effectiveTimezone()).format('Z').slice(0, 3)}
                    </span>
                  )}
                </Space>
              </Button>
            </Dropdown>
            {/* 偏好抽屉入口（REQ-20261121-010725 AC-5）：带已选强调色圆点提示 */}
            <Button
              type="text"
              onClick={() => setPrefsOpen(true)}
              aria-label={t('settings.preferences.open')}
              style={{ position: 'relative', paddingInline: 8 }}
            >
              <BgColorsOutlined />
              <span
                aria-hidden
                style={{
                  position: 'absolute',
                  top: 5,
                  right: 4,
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  background: accentHex,
                  border: '1px solid var(--surface)',
                }}
              />
            </Button>
            <Button
              type="text"
              icon={resolvedMode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
              onClick={toggleThemeMode}
              aria-label={t('nav.header.toggleTheme')}
            />
            <Button
              type="text"
              icon={<LockOutlined />}
              onClick={() => useLockStore.getState().lock()}
              aria-label={t('nav.header.lockScreen')}
            />
            <Button
              type="text"
              icon={isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
              onClick={() => (isFullscreen ? exitFullscreen() : enterFullscreen())}
              aria-label={isFullscreen ? t('nav.header.exitFullscreen') : t('nav.header.enterFullscreen')}
            />
            <Button
              type="text"
              icon={<SettingOutlined />}
              onClick={() => go('/settings')}
              aria-label={t('nav.header.settings')}
            />
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: onUserMenuClick,
              }}
              onOpenChange={(open) => {
                // 菜单展开时才拉取目标租户目录（公开端点，轻量）；仅超管需要
                if (open && isSuperAdmin) {
                  loadSwitchTargets();
                }
              }}
            >
              <Button type="text" aria-label={t('nav.header.openUserMenu')} style={{ paddingInline: 6 }}>
                <Space size={6}>
                  {/* 个人中心（REQ-20261119-082531 契约⑦）：用户区头像（avatarUrl 或昵称首字符） */}
                  <Avatar avatarUrl={me?.user.avatarUrl} name={displayName} size={26} />
                  {!isMobile && <DownOutlined style={{ fontSize: 10, color: 'var(--muted)' }} />}
                </Space>
              </Button>
            </Dropdown>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => openModal({ type: 'release', id: null })}
              aria-label={t('nav.header.createAria')}
            >
              {!isMobile && t('nav.header.create')}
            </Button>
          </Header>
          {/* 页签条（REQ-20261119-063129 契约③）：Header 下方自绘 pill 条；逃生口模式下不渲染 */}
          {!tabsDisabled && (
            <TabBar
              iconOf={tabIconOf}
              onSelect={handleTabSelect}
              onClose={handleTabClose}
              onRefresh={handleTabRefresh}
              onTogglePin={handleTabTogglePin}
              onCloseOthers={handleTabCloseOthers}
              onCloseRight={handleTabCloseRight}
              onCloseAll={handleTabCloseAll}
            />
          )}
          {/* 内容区视口内自滚：minHeight 0 解除 flex 子项最小尺寸约束，整页高度不随内容撑大。
              内容包裹层 .content-body 承载内容宽度档位（REQ-20261121-043502 确认⑥）：
              fluid=不限宽现状；compact=定宽居中（global.css .content-body[data-width]）。 */}
          <Content ref={contentRef} style={{ padding: isMobile ? '16px 14px 60px' : '24px 30px 60px', flex: 1, minHeight: 0, overflow: 'auto' }}>
            <div className="content-body" data-width={preferences.contentWidth}>
              {(storageError || storageConflict) && (
                <Alert
                  type="warning"
                  showIcon
                  role="alert"
                  style={{ marginBottom: 16 }}
                  message={storageError ? t('nav.storage.unsaved') : t('nav.storage.conflict')}
                  description={storageError ? t('nav.storage.unsavedDesc') : t('nav.storage.conflictDesc')}
                  action={<Button onClick={() => download('acme-unsaved-backup.json', JSON.stringify(useStore.getState().state, null, 2))}>{t('nav.storage.export')}</Button>}
                />
              )}
              {/* 渲染与 router 脱钩（REQ-20261119-063129 契约①）：常规=TabKeepAlive 按 tabsStore
                  已开页签渲染（keepalive）；逃生口（契约⑦ window.__ACME_TABS_DISABLED__）=单页路由
                  渲染（PAGE_ROUTES 逐页 PageBoundary+PermissionGate），仅供个别测试装配隔离。 */}
              {tabsDisabled ? (
                <Routes>
                  {PAGE_ROUTES.map((route) => (
                    <Route
                      key={route.path}
                      path={route.routePath ?? route.path}
                      element={(
                        <PageBoundary>
                          {route.permission
                            ? <PermissionGate permission={route.permission}><DateTimePrefsRefresh>{route.element}</DateTimePrefsRefresh></PermissionGate>
                            : <DateTimePrefsRefresh>{route.element}</DateTimePrefsRefresh>}
                        </PageBoundary>
                      )}
                    />
                  ))}
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
              ) : (
                <TabKeepAlive />
              )}
              {/* 应用内页脚版权（REQ-20261117-025200，AC-10）：租户配置 footerVisible 且文案非空才渲染
                  （文案空串/null 一律不渲染空标签）；普通文档流不遮挡内容，水印覆盖属预期 */}
              {footerVisible && footerCopyright ? <footer className="app-foot">{footerCopyright}</footer> : null}
            </div>
          </Content>
        </Layout>
      </Layout>

      <Drawer
        placement="left"
        open={mobileNav}
        onClose={() => setMobileNav(false)}
        width={260}
        closable={false}
        styles={{ body: { padding: 0 } }}
      >
        {renderNav(true)}
      </Drawer>

      <ReleaseDrawer />
      <ReleaseFormModal />
      <SaveViewModal />
      <AppFormModal />
      <LockScreenOverlay />
      {/* 偏好抽屉（REQ-20261121-010725）：顶栏入口打开，修改即时生效并持久化 */}
      <PreferencesDrawer open={prefsOpen} onClose={() => setPrefsOpen(false)} />
      {/* 命令面板（REQ-20261119-063129 契约⑤）：Ctrl+K 开启；动作复用 App 既有 handlers */}
      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        onOpenPage={(path) => {
          setPaletteOpen(false);
          navigate(path);
        }}
        onLock={() => {
          setPaletteOpen(false);
          useLockStore.getState().lock();
        }}
        onToggleTheme={() => {
          toggleThemeMode();
        }}
        onToggleLanguage={() => {
          setLocale(locale === 'zh-CN' ? 'en-US' : 'zh-CN');
        }}
        onCreateRelease={() => {
          setPaletteOpen(false);
          openModal({ type: 'release', id: null });
        }}
        onLogout={() => {
          setPaletteOpen(false);
          handleLogout();
        }}
        hasPermission={hasPermission}
      />
    </>
  );
  // App 级水印（AC-7；REQ-20261117-180000 模板化）：内容=模板渲染（未配置=仅账号）；
  // 租户配置关闭（或未登录）不渲染
  const watermarkContent = renderWatermarkTemplate(watermarkTemplate, {
    username: session?.username ?? '',
    nickname: session?.nickname,
    userId: session?.userId,
    tenantCode: session?.tenantCode,
    deptName: me?.deptName ?? null,
  }, watermarkNow);
  return watermarkEnabled && session ? (
    <Watermark content={watermarkContent} style={{ minHeight: '100vh' }}>{shell}</Watermark>
  ) : (
    shell
  );
}
