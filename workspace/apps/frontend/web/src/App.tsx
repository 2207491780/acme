import { useEffect, useRef, useState } from 'react';
import { Alert, Badge, Button, Drawer, Grid, Input, Layout, Menu, Tooltip } from 'antd';
import type { InputRef, MenuProps } from 'antd';
import {
  AppstoreOutlined,
  DashboardOutlined,
  MenuFoldOutlined,
  MenuOutlined,
  MoonOutlined,
  PlusOutlined,
  SearchOutlined,
  SettingOutlined,
  SunOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ACTIVE_STATUSES } from '@acme/core';
import { useStore } from './store/useStore';
import { detectPlatform, download } from './utils/dom';
import { Dashboard } from './pages/Dashboard';
import { Releases } from './pages/Releases';
import { Apps } from './pages/Apps';
import { Settings } from './pages/Settings';
import { ReleaseDrawer } from './components/ReleaseDrawer';
import { ReleaseFormModal } from './components/ReleaseFormModal';
import { SaveViewModal } from './components/SaveViewModal';
import { AppFormModal } from './components/AppFormModal';

const { Sider, Header, Content } = Layout;

const NAV = [
  { key: 'dashboard', path: '/', icon: <DashboardOutlined />, label: '概览' },
  { key: 'releases', path: '/releases', icon: <UnorderedListOutlined />, label: '发布单' },
  { key: 'apps', path: '/apps', icon: <AppstoreOutlined />, label: '应用结构' },
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

  const activeCount = releases.filter((r) => ACTIVE_STATUSES.includes(r.status)).length;
  const current = location.pathname === '/' ? 'dashboard' : location.pathname.slice(1);
  const platform = detectPlatform();
  const runtimeLabel = platform === 'desktop' ? 'Electron 桌面端' : platform === 'mobile' ? '移动端' : 'Web 端';

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

  const go = (path: string) => {
    navigate(path);
    setMobileNav(false);
  };

  const onSearch = (value: string) => {
    setQuery({ search: value });
    if (location.pathname !== '/releases') navigate('/releases');
  };

  const menuItems: MenuProps['items'] = NAV.map((n) => ({
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
        onClick={({ key }) => go(NAV.find((n) => n.key === key)?.path || '/')}
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
              <Route path="/settings" element={<Settings />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>

      {isMobile && (
        <nav className="mobile-nav" aria-label="移动导航">
          {NAV.map((n) => (
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