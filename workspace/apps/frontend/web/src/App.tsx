import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, App as AntApp, Avatar, Badge, Button, Card, Drawer, Dropdown, Grid, Input, Layout, Menu, Space, Watermark } from 'antd';
import type { InputRef, MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  ApartmentOutlined,
  AppstoreOutlined,
  BookOutlined,
  ControlOutlined,
  DashboardOutlined,
  DownOutlined,
  FieldTimeOutlined,
  FileSearchOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
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
  SearchOutlined,
  SettingOutlined,
  SunOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ACTIVE_STATUSES, ApiError, authApi, effectiveTimezone, renderWatermarkTemplate, startAuthRenewal, SUPPORTED_LOCALES, SUPPORTED_TIMEZONES, useAuthStore, useLocaleStore, useLockStore, useMeStore, useNotifyStore, useSystemConfigStore, useTimezoneStore, type AppLocale, type TenantSummary, type TimezonePref } from '@acme/core';
import dayjs from 'dayjs';
import { useStore } from './store/useStore';
import { detectPlatform, download } from './utils/dom';
import { createNotifyClient } from './ws/notify-client';
import { Dashboard } from './pages/Dashboard';
import { Releases } from './pages/Releases';
import { Apps } from './pages/Apps';
import { Settings } from './pages/Settings';
import { TenantsPage } from './pages/TenantsPage';
import { UsersPage } from './pages/UsersPage';
import { RolesPage } from './pages/RolesPage';
import { DeptsPage } from './pages/DeptsPage';
import { PositionsPage } from './pages/PositionsPage';
import { PermissionsPage } from './pages/PermissionsPage';
import { MenusPage } from './pages/MenusPage';
import { DictsPage } from './pages/DictsPage';
import { SystemConfigPage } from './pages/SystemConfigPage';
import { AuditPage } from './pages/AuditPage';
import { LoginLogsPage } from './pages/LoginLogsPage';
import { OnlineUsersPage } from './pages/OnlineUsersPage';
import { FilesPage } from './pages/FilesPage';
import { NoticesPage } from './pages/NoticesPage';
import { LoginPage } from './pages/LoginPage';
import { NotificationBell } from './components/NotificationBell';
import { ErrorBoundary } from './components/ErrorBoundary';
import { buildDynamicNav, type NavEntry } from './nav';
import { ReleaseDrawer } from './components/ReleaseDrawer';
import { ReleaseFormModal } from './components/ReleaseFormModal';
import { SaveViewModal } from './components/SaveViewModal';
import { AppFormModal } from './components/AppFormModal';
import { LockScreenOverlay } from './components/LockScreenOverlay';
import { useFullscreen } from './hooks/useFullscreen';

const { Sider, Header, Content } = Layout;

/**
 * 页面级权限门禁（p2-admin）：画像未加载放行占位（避免闪烁），已加载缺权限码 → 403 提示。
 * 加载失败进入错误态并给出重试入口（review 修复：此前失败后所有权限页永远停留在“加载中”）。
 */
function PermissionGate({ permission, children }: { permission: string; children: ReactNode }) {
  const { t } = useTranslation();
  const me = useMeStore((s) => s.me);
  const loading = useMeStore((s) => s.loading);
  const error = useMeStore((s) => s.error);
  if (!me) {
    if (error && !loading) {
      return (
        <Card>
          <Alert
            type="error"
            showIcon
            message={t('nav.permissionGate.loadFailed')}
            description={t('nav.permissionGate.loadFailedDesc', { error })}
            action={
              <Button size="small" onClick={() => void useMeStore.getState().load()}>
                {t('common.actions.retry')}
              </Button>
            }
          />
        </Card>
      );
    }
    return <div style={{ padding: 32, textAlign: 'center', color: 'var(--muted)' }}>{t('nav.permissionGate.loading')}</div>;
  }
  if (!me.permissions.includes(permission)) {
    return (
      <Card>
        <Alert
          type="warning"
          showIcon
          message={t('nav.permissionGate.denied')}
          description={t('nav.permissionGate.deniedDesc', { permission })}
        />
      </Card>
    );
  }
  return <>{children}</>;
}

/** 页面级局部错误边界：路由页面渲染抛错 → 局部兜底 UI + 重试（REQ-20261114-120103，AC-3）。 */
function PageBoundary({ children }: { children: ReactNode }) {
  return <ErrorBoundary variant="page">{children}</ErrorBoundary>;
}

const NAV: { path: string; icon: ReactNode; labelKey: string; permission?: string }[] = [
  { path: '/', icon: <DashboardOutlined />, labelKey: 'nav.items.dashboard' },
  { path: '/releases', icon: <UnorderedListOutlined />, labelKey: 'nav.items.releases' },
  { path: '/apps', icon: <AppstoreOutlined />, labelKey: 'nav.items.apps' },
  { path: '/tenants', icon: <ApartmentOutlined />, labelKey: 'nav.items.tenants', permission: 'system:tenant:list' },
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
  { path: '/settings', icon: <SettingOutlined />, labelKey: 'nav.items.settings' },
];

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
  // 超管跨租户切换（REQ-20261117-025200）：目标租户目录（用户菜单展开时拉取）+ 切换中防重复点击
  const [switchTargets, setSwitchTargets] = useState<TenantSummary[]>([]);
  const [switchTargetsLoading, setSwitchTargetsLoading] = useState(false);
  const [switchingTenant, setSwitchingTenant] = useState(false);
  const searchRef = useRef<InputRef>(null);

  // 登录态（login-state / login-renew）：未登录渲染独立登录页；已登录进入控制台并启动滑动续约
  const session = useAuthStore((s) => s.session);
  const isAuthed = session !== null;
  useEffect(() => startAuthRenewal(), []);

  // 会话恢复校验（p1-me-sync）：进入应用即拉取服务端画像（me 401 由 client 续约/清会话处理）。
  // 依赖收敛为 isAuthed：滑动续约会整体替换 session 对象，以 session 为依赖会导致每次续约重复拉取 me。
  // 租户系统配置（REQ-20261116-230354）随会话就绪加载（水印显隐 + 闲置锁屏时长 + 页脚版权强制生效）；
  // 登出/会话失效复位为缺省（水印开/验证码关/闲置关/页脚不显示）并解除闲置计时。
  useEffect(() => {
    if (isAuthed) {
      void useMeStore.getState().load();
      void useSystemConfigStore.getState().load();
    } else {
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
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = settings.theme;
    root.dataset.accent = settings.accent;
    root.dataset.density = settings.density;
    root.dataset.motion = String(settings.motion);
  }, [settings]);

  useEffect(() => {
    const timer = setInterval(() => dispatch({ type: 'TICK' }), 1200);
    return () => clearInterval(timer);
  }, [dispatch]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchRef.current?.focus();
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

  const onSearch = (value: string) => {
    setQuery({ search: value });
    if (location.pathname !== '/releases') navigate('/releases');
  };

  const onUserMenuClick: MenuProps['onClick'] = ({ key }) => {
    if (key === 'logout') {
      handleLogout();
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
        style={{ border: 'none', padding: '0 8px' }}
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
                  <kbd style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--muted)', border: '1px solid var(--border)', borderRadius: 6, padding: '1px 6px' }}>
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
            <Button
              type="text"
              icon={settings.theme === 'dark' ? <SunOutlined /> : <MoonOutlined />}
              onClick={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: settings.theme === 'dark' ? 'light' : 'dark' } })}
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
                  <Avatar size={26} style={{ background: 'var(--accent)' }} icon={<UserOutlined />} />
                  {!isMobile && <DownOutlined style={{ fontSize: 10, color: 'var(--muted)' }} />}
                </Space>
              </Button>
            </Dropdown>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ type: 'release', id: null })}>
              {!isMobile && t('nav.header.create')}
            </Button>
          </Header>
          {/* 内容区视口内自滚：minHeight 0 解除 flex 子项最小尺寸约束，整页高度不随内容撑大 */}
          <Content style={{ padding: isMobile ? '16px 14px 60px' : '24px 30px 60px', flex: 1, minHeight: 0, overflow: 'auto' }}>
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
            <Routes>
              <Route path="/" element={<PageBoundary><Dashboard /></PageBoundary>} />
              <Route path="/releases" element={<PageBoundary><Releases /></PageBoundary>} />
              <Route path="/apps" element={<PageBoundary><Apps /></PageBoundary>} />
              <Route
                path="/tenants"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:tenant:list">
                      <TenantsPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/users"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:user:list">
                      <UsersPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/roles"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:role:list">
                      <RolesPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/depts"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:dept:list">
                      <DeptsPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/positions"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:position:list">
                      <PositionsPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/permissions"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:permission:list">
                      <PermissionsPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/menus"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:menu:list">
                      <MenusPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/dicts"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:dict:list">
                      <DictsPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/notices"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="system:notice:list">
                      <NoticesPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/audit"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="infra:audit:list">
                      <AuditPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/login-logs"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="auth:loginlog:list">
                      <LoginLogsPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/online-users"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="auth:online:list">
                      <OnlineUsersPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              <Route
                path="/files"
                element={(
                  <PageBoundary>
                    <PermissionGate permission="infra:file:list">
                      <FilesPage />
                    </PermissionGate>
                  </PageBoundary>
                )}
              />
              {/* 系统配置（REQ-20261116-230354）：GET 认证即可读，页面不设 PermissionGate；
                  保存能力在页面内按 system:config:update fail-closed（AC-6） */}
              <Route path="/system/config" element={<PageBoundary><SystemConfigPage /></PageBoundary>} />
              <Route path="/settings" element={<PageBoundary><Settings /></PageBoundary>} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
            {/* 应用内页脚版权（REQ-20261117-025200，AC-10）：租户配置 footerVisible 且文案非空才渲染
                （文案空串/null 一律不渲染空标签）；普通文档流不遮挡内容，水印覆盖属预期 */}
            {footerVisible && footerCopyright ? <footer className="app-foot">{footerCopyright}</footer> : null}
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
