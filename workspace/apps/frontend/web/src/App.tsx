import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, Avatar, Badge, Button, Card, Drawer, Dropdown, Grid, Input, Layout, Menu, Space, Tooltip } from 'antd';
import type { InputRef, MenuProps } from 'antd';
import {
  ApartmentOutlined,
  AppstoreOutlined,
  BookOutlined,
  DashboardOutlined,
  DownOutlined,
  FileSearchOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuOutlined,
  MoonOutlined,
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
import { ACTIVE_STATUSES, authApi, startAuthRenewal, useAuthStore, useMeStore } from '@acme/core';
import { useStore } from './store/useStore';
import { detectPlatform, download } from './utils/dom';
import { Dashboard } from './pages/Dashboard';
import { Releases } from './pages/Releases';
import { Apps } from './pages/Apps';
import { Settings } from './pages/Settings';
import { TenantsPage } from './pages/TenantsPage';
import { UsersPage } from './pages/UsersPage';
import { RolesPage } from './pages/RolesPage';
import { PermissionsPage } from './pages/PermissionsPage';
import { MenusPage } from './pages/MenusPage';
import { DictsPage } from './pages/DictsPage';
import { AuditPage } from './pages/AuditPage';
import { LoginPage } from './pages/LoginPage';
import { ReleaseDrawer } from './components/ReleaseDrawer';
import { ReleaseFormModal } from './components/ReleaseFormModal';
import { SaveViewModal } from './components/SaveViewModal';
import { AppFormModal } from './components/AppFormModal';

const { Sider, Header, Content } = Layout;

/**
 * 页面级权限门禁（p2-admin）：画像未加载放行占位（避免闪烁），已加载缺权限码 → 403 提示。
 * 加载失败进入错误态并给出重试入口（review 修复：此前失败后所有权限页永远停留在“加载中”）。
 */
function PermissionGate({ permission, children }: { permission: string; children: ReactNode }) {
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
            message="权限信息加载失败"
            description={`${error}。权限入口暂不可用，请重试或刷新页面。`}
            action={
              <Button size="small" onClick={() => void useMeStore.getState().load()}>
                重试
              </Button>
            }
          />
        </Card>
      );
    }
    return <div style={{ padding: 32, textAlign: 'center', color: 'var(--muted)' }}>权限信息加载中…</div>;
  }
  if (!me.permissions.includes(permission)) {
    return (
      <Card>
        <Alert
          type="warning"
          showIcon
          message="无访问权限"
          description={`缺少权限码 ${permission}，请联系租户管理员为当前账号分配对应角色。`}
        />
      </Card>
    );
  }
  return <>{children}</>;
}

const NAV: { key: string; path: string; icon: ReactNode; label: string; permission?: string }[] = [
  { key: 'dashboard', path: '/', icon: <DashboardOutlined />, label: '概览' },
  { key: 'releases', path: '/releases', icon: <UnorderedListOutlined />, label: '发布单' },
  { key: 'apps', path: '/apps', icon: <AppstoreOutlined />, label: '应用结构' },
  { key: 'tenants', path: '/tenants', icon: <ApartmentOutlined />, label: '租户管理', permission: 'TENANT_LIST' },
  { key: 'users', path: '/users', icon: <UserOutlined />, label: '用户管理', permission: 'USER_LIST' },
  { key: 'roles', path: '/roles', icon: <TeamOutlined />, label: '角色管理', permission: 'system:role:list' },
  { key: 'permissions', path: '/permissions', icon: <SafetyCertificateOutlined />, label: '权限管理', permission: 'system:permission:list' },
  { key: 'menus', path: '/menus', icon: <MenuOutlined />, label: '菜单管理', permission: 'system:menu:list' },
  { key: 'dicts', path: '/dicts', icon: <BookOutlined />, label: '字典管理', permission: 'system:dict:list' },
  { key: 'audit', path: '/audit', icon: <FileSearchOutlined />, label: '审计事件', permission: 'infra:audit:list' },
  { key: 'settings', path: '/settings', icon: <SettingOutlined />, label: '设置' },
];

export function App() {
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
  const userMenuItems: MenuProps['items'] = [
    { key: 'who', label: `${displayName}（租户 ${session?.tenantCode}）`, disabled: true },
    {
      key: 'roles',
      label: `角色：${me?.roles.join('、') || '加载中…'}`,
      disabled: true,
    },
    { type: 'divider' },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
  ];

  if (!session) {
    return <LoginPage />;
  }

  const activeCount = releases.filter((r) => ACTIVE_STATUSES.includes(r.status)).length;
  const current = location.pathname === '/' ? 'dashboard' : location.pathname.slice(1);
  const platform = detectPlatform();
  const runtimeLabel = platform === 'desktop' ? 'Electron 桌面端' : platform === 'mobile' ? '移动端' : 'Web 端';

  const go = (path: string) => {
    navigate(path);
    setMobileNav(false);
  };

  const onSearch = (value: string) => {
    setQuery({ search: value });
    if (location.pathname !== '/releases') navigate('/releases');
  };

  // 导航按权限过滤（p2-admin）：me 未加载时先全部隐藏，加载后按权限码展示（fail-closed）
  const permittedNav = NAV.filter((n) => !n.permission || (me?.permissions.includes(n.permission) ?? false));
  const menuItems: MenuProps['items'] = permittedNav.map((n) => ({
    key: n.key,
    icon: n.icon,
    label:
      n.key === 'releases' ? (
        <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {n.label}
          <Badge count={activeCount} size="small" color="var(--accent)" />
        </span>
      ) : (
        n.label
      ),
  }));

  const renderNav = (expanded: boolean) => (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div className="brand">
        <div className="brand-mark">
          <ThunderboltOutlined style={{ fontSize: 22 }} />
        </div>
        {expanded && (
          <div>
            <div className="brand-title">ACME 交付台</div>
            <div className="brand-sub">Frontend Ops</div>
          </div>
        )}
      </div>
      <Menu
        mode="inline"
        selectedKeys={[current]}
        items={menuItems}
        onClick={({ key }) => go(permittedNav.find((n) => n.key === key)?.path || '/')}
        style={{ border: 'none', padding: '0 8px' }}
      />
      {expanded && (
        <div className="sidebar-foot">
          <strong>多端复用</strong>
          Web 端是页面唯一来源；Electron 套壳 Web 产物；移动端共享业务层并适配小屏。
        </div>
      )}
    </div>
  );

  return (
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
              aria-label="切换导航"
            />
            <Input
              ref={searchRef}
              allowClear
              prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
              placeholder="搜索发布单 / 版本 / 负责人…"
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
            <Tooltip title="切换主题">
              <Button
                type="text"
                icon={settings.theme === 'dark' ? <SunOutlined /> : <MoonOutlined />}
                onClick={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: settings.theme === 'dark' ? 'light' : 'dark' } })}
                aria-label="切换主题"
              />
            </Tooltip>
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: ({ key }) => {
                  if (key === 'logout') {
                    handleLogout();
                  }
                },
              }}
            >
              <Button type="text" aria-label="打开用户菜单" style={{ paddingInline: 6 }}>
                <Space size={6}>
                  <Avatar size={26} style={{ background: 'var(--accent)' }} icon={<UserOutlined />} />
                  {!isMobile && <DownOutlined style={{ fontSize: 10, color: 'var(--muted)' }} />}
                </Space>
              </Button>
            </Dropdown>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ type: 'release', id: null })}>
              {!isMobile && '新建'}
            </Button>
          </Header>
          <Content style={{ padding: isMobile ? '16px 14px 88px' : '24px 30px 60px' }}>
            {(storageError || storageConflict) && (
              <Alert
                type="warning"
                showIcon
                role="alert"
                style={{ marginBottom: 16 }}
                message={storageError ? '数据尚未保存到本地' : '检测到其他窗口的数据变更'}
                description={storageError
                  ? '本页数据仅保留在内存中，请先导出备份再刷新或关闭页面。'
                  : '本次操作已暂停，请检查数据后重试；如有未完成的修改，请先导出备份。'}
                action={<Button onClick={() => download('acme-unsaved-backup.json', JSON.stringify(useStore.getState().state, null, 2))}>导出本页数据</Button>}
              />
            )}
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/releases" element={<Releases />} />
              <Route path="/apps" element={<Apps />} />
              <Route
                path="/tenants"
                element={(
                  <PermissionGate permission="TENANT_LIST">
                    <TenantsPage />
                  </PermissionGate>
                )}
              />
              <Route
                path="/users"
                element={(
                  <PermissionGate permission="USER_LIST">
                    <UsersPage />
                  </PermissionGate>
                )}
              />
              <Route
                path="/roles"
                element={(
                  <PermissionGate permission="system:role:list">
                    <RolesPage />
                  </PermissionGate>
                )}
              />
              <Route
                path="/permissions"
                element={(
                  <PermissionGate permission="system:permission:list">
                    <PermissionsPage />
                  </PermissionGate>
                )}
              />
              <Route
                path="/menus"
                element={(
                  <PermissionGate permission="system:menu:list">
                    <MenusPage />
                  </PermissionGate>
                )}
              />
              <Route
                path="/dicts"
                element={(
                  <PermissionGate permission="system:dict:list">
                    <DictsPage />
                  </PermissionGate>
                )}
              />
              <Route
                path="/audit"
                element={(
                  <PermissionGate permission="infra:audit:list">
                    <AuditPage />
                  </PermissionGate>
                )}
              />
              <Route path="/settings" element={<Settings />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>

      {isMobile && (
        <nav className="mobile-nav" aria-label="移动导航">
          {permittedNav.map((n) => (
            <button key={n.key} className={current === n.key ? 'active' : ''} onClick={() => go(n.path)}>
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
    </>
  );
}