import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, Avatar, Badge, Button, Card, Drawer, Dropdown, Grid, Input, Layout, Menu, Space, Tooltip, Watermark } from 'antd';
import type { InputRef, MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  ApartmentOutlined,
  AppstoreOutlined,
  BookOutlined,
  DashboardOutlined,
  DownOutlined,
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
import { ACTIVE_STATUSES, authApi, startAuthRenewal, useAuthStore, useLocaleStore, useLockStore, useMeStore, useNotifyStore, useUiPrefsStore, type AppLocale } from '@acme/core';
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
  { path: '/notices', icon: <NotificationOutlined />, labelKey: 'nav.items.notices', permission: 'system:notice:list' },
  { path: '/audit', icon: <FileSearchOutlined />, labelKey: 'nav.items.audit', permission: 'infra:audit:list' },
  { path: '/login-logs', icon: <HistoryOutlined />, labelKey: 'nav.items.loginLogs', permission: 'auth:loginlog:list' },
  { path: '/online-users', icon: <TeamOutlined />, labelKey: 'nav.items.onlineUsers', permission: 'auth:online:list' },
  { path: '/settings', icon: <SettingOutlined />, labelKey: 'nav.items.settings' },
];

export function App() {
  const { t } = useTranslation();
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
  const searchRef = useRef<InputRef>(null);

  // 登录态（login-state / login-renew）：未登录渲染独立登录页；已登录进入控制台并启动滑动续约
  const session = useAuthStore((s) => s.session);
  const isAuthed = session !== null;
  useEffect(() => startAuthRenewal(), []);

  // 会话恢复校验（p1-me-sync）：进入应用即拉取服务端画像（me 401 由 client 续约/清会话处理）。
  // 依赖收敛为 isAuthed：滑动续约会整体替换 session 对象，以 session 为依赖会导致每次续约重复拉取 me。
  useEffect(() => {
    if (isAuthed) {
      void useMeStore.getState().load();
    } else {
      useMeStore.getState().clear();
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

  // 全屏 / 水印（REQ-20261114-120112，AC-3/AC-5）：水印开关即时生效（Settings 可关）
  const { isFullscreen, enter: enterFullscreen, exit: exitFullscreen } = useFullscreen();
  const watermarkEnabled = useUiPrefsStore((s) => s.watermarkEnabled);

  // 闲置锁屏（AC-4）：进入应用武装闲置计时（时长来自 UI 偏好，0=关闭）；用户活动打点重置计时
  useEffect(() => {
    useLockStore.getState().setIdleTimeoutMs(useUiPrefsStore.getState().lockIdleTimeoutMs);
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
  const userMenuItems: MenuProps['items'] = [
    { key: 'who', label: t('nav.userMenu.tenantLabel', { name: displayName, tenant: session?.tenantCode ?? '' }), disabled: true },
    {
      key: 'roles',
      label: t('nav.userMenu.rolesLabel', { roles: rolesText }),
      disabled: true,
    },
    { type: 'divider' },
    {
      key: 'locale',
      icon: <GlobalOutlined />,
      label: t('nav.userMenu.language'),
      children: [
        { key: 'locale-zh-CN', label: t('nav.userMenu.languageZh') },
        { key: 'locale-en-US', label: t('nav.userMenu.languageEn') },
      ],
    },
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
  const setLocale = useLocaleStore((s) => s.setLocale);
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
    if (key.startsWith('locale-')) {
      setLocale(key.slice('locale-'.length) as AppLocale);
    }
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
      <Layout style={{ minHeight: '100vh' }}>
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
        <Layout>
          <Header style={{ display: 'flex', alignItems: 'center', gap: 12, position: 'sticky', top: 0, zIndex: 20 }}>
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
            <Tooltip title={t('nav.header.toggleTheme')}>
              <Button
                type="text"
                icon={settings.theme === 'dark' ? <SunOutlined /> : <MoonOutlined />}
                onClick={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: settings.theme === 'dark' ? 'light' : 'dark' } })}
                aria-label={t('nav.header.toggleTheme')}
              />
            </Tooltip>
            <Tooltip title={t('nav.header.lockScreen')}>
              <Button
                type="text"
                icon={<LockOutlined />}
                onClick={() => useLockStore.getState().lock()}
                aria-label={t('nav.header.lockScreen')}
              />
            </Tooltip>
            <Tooltip title={isFullscreen ? t('nav.header.exitFullscreen') : t('nav.header.enterFullscreen')}>
              <Button
                type="text"
                icon={isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
                onClick={() => (isFullscreen ? exitFullscreen() : enterFullscreen())}
                aria-label={isFullscreen ? t('nav.header.exitFullscreen') : t('nav.header.enterFullscreen')}
              />
            </Tooltip>
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: onUserMenuClick,
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
          <Content style={{ padding: isMobile ? '16px 14px 88px' : '24px 30px 60px' }}>
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
              <Route path="/settings" element={<PageBoundary><Settings /></PageBoundary>} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>

      {isMobile && (
        <nav className="mobile-nav" aria-label={t('nav.mobileNav')}>
          {navEntries.map((n) => (
            <button key={n.path} className={current === n.path ? 'active' : ''} onClick={() => go(n.path)}>
              {n.icon}
              <span>{n.label}</span>
            </button>
          ))}
        </nav>
      )}

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
  // App 级水印（AC-3）：内容=当前用户账号；Settings 关闭（或未登录）立即消失并持久化
  return watermarkEnabled && session ? (
    <Watermark content={session.username} style={{ minHeight: '100vh' }}>{shell}</Watermark>
  ) : (
    shell
  );
}
