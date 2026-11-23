import { type Ref, type ReactNode } from 'react';
import { Button, Drawer, Layout } from 'antd';
import { useTranslation } from 'react-i18next';
import { MenuFoldOutlined, MenuOutlined, ThunderboltOutlined } from '@ant-design/icons';
import type { LayoutMode } from '@acme/core';

const { Sider, Header, Content } = Layout;

/**
 * 应用外壳布局（REQ-20261122-233427 THEME-4 确认③）：从 App.tsx 抽出的布局组件文件——
 * 按偏好 layoutMode 编排三种桌面布局，移动端行为不涉（保留 Drawer 现状，三模式同壳）。
 *
 * - sidebar（缺省=现状逐字）：左 Sider（248/72 折叠）+ 右列 Header/页签条/Content；
 * - top：顶栏主导航（菜单横向化，由 App 注入 horizontalNav）+ 无 Sider 全宽内容 + 页签条；
 * - mixed：顶栏一级横向主导航 + 左侧 72px 图标轨（折叠态 Sider 语义，常驻 collapsed）+ 内容。
 *
 * 热切换（确认③）：layoutMode 读偏好即时生效、无页面刷新；内层 Layout 以 key="app-main"、
 * Sider 以 key="app-sider" 锚定——React 按 key 对账，三模式切换时 Content/保活子树不重挂
 * （路由与页签内页面状态保持），仅 Sider 按需挂载/卸载。
 * AppLayout 为纯编排组件：导航/顶栏 widget/页签条/内容主体由 App 装配注入。
 */
export interface AppLayoutProps {
  /** 布局模式（读偏好 layoutMode；缺省 sidebar=现状）。 */
  layoutMode: LayoutMode;
  /** <lg 视为移动布局（Grid.useBreakpoint，App 注入）。 */
  isMobile: boolean;
  /** sidebar 模式桌面折叠态（现状语义；top/mixed 桌面不消费）。 */
  collapsed: boolean;
  onToggleCollapsed: () => void;
  mobileNavOpen: boolean;
  onMobileNavOpen: () => void;
  onMobileNavClose: () => void;
  /** 侧边导航（brand+inline Menu+foot）：sidebar/mixed 桌面 Sider 与移动 Drawer 复用。 */
  renderSiderNav: (expanded: boolean) => ReactNode;
  /** 顶栏横向主导航（top=全量菜单横向化；mixed=一级）；sidebar/移动端不渲染。 */
  horizontalNav?: ReactNode;
  /** 顶栏 widget 群（搜索/铃铛/... ；App 按 topbarWidgets 偏好装配，现状=全显逐字）。 */
  headerWidgets: ReactNode;
  /** 页签条（逃生口 __ACME_TABS_DISABLED__ 模式为 null）。 */
  tabBar?: ReactNode;
  /** 内容区主体（content-body 内层，App 装配）。 */
  content: ReactNode;
  /** Content 滚动视口 ref（App 记忆/恢复切签滚动位置）。 */
  contentRef?: Ref<HTMLElement>;
  /** 内容宽度档位（content-body data-width，THEME-2 确认⑥接线）。 */
  contentWidth?: string;
}

export function AppLayout({
  layoutMode,
  isMobile,
  collapsed,
  onToggleCollapsed,
  mobileNavOpen,
  onMobileNavOpen,
  onMobileNavClose,
  renderSiderNav,
  horizontalNav,
  headerWidgets,
  tabBar,
  content,
  contentRef,
  contentWidth,
}: AppLayoutProps) {
  const { t } = useTranslation();
  const desktop = !isMobile;
  const showSider = desktop && (layoutMode === 'sidebar' || layoutMode === 'mixed');
  // mixed 模式图标轨=折叠态 72px 语义（确认③）：常驻 collapsed，不消费 sidebar 折叠态
  const siderCollapsed = layoutMode === 'mixed' ? true : collapsed;

  // 顶栏 leading 位（现状顺序首位）：sidebar 桌面/移动端=导航开合钮（现状逐字）；
  // top/mixed 桌面=品牌标（Sider 缺位/图标轨常驻，无折叠语义）
  const leading = desktop && layoutMode !== 'sidebar' ? (
    <div className="brand" style={{ padding: 0, flex: 'none' }}>
      <div className="brand-mark">
        <ThunderboltOutlined style={{ fontSize: 22 }} />
      </div>
      <div>
        <div className="brand-title">{t('nav.brand.title')}</div>
      </div>
    </div>
  ) : (
    <Button
      type="text"
      icon={isMobile || collapsed ? <MenuOutlined /> : <MenuFoldOutlined />}
      onClick={() => (isMobile ? onMobileNavOpen() : onToggleCollapsed())}
      aria-label={t('nav.header.toggleNav')}
    />
  );

  const siderNode = showSider ? (
    <Sider
      key="app-sider"
      collapsible
      trigger={null}
      collapsed={siderCollapsed}
      width={248}
      collapsedWidth={72}
      style={{ position: 'sticky', top: 0, height: '100vh' }}
    >
      {renderSiderNav(!siderCollapsed)}
    </Sider>
  ) : null;

  return (
    <>
      <Layout style={{ height: '100vh', overflow: 'hidden' }}>
        {siderNode}
        {/* key 锚定热切换（确认③）：三模式对账保留 Content 子树（保活/滚动位置不重挂） */}
        <Layout key="app-main" style={{ height: '100%', minWidth: 0, overflow: 'hidden' }}>
          <Header style={{ display: 'flex', alignItems: 'center', gap: 12, position: 'sticky', top: 0, zIndex: 20, flexShrink: 0 }}>
            {leading}
            {desktop && horizontalNav != null ? (
              <div style={{ flex: 1, minWidth: 0 }}>{horizontalNav}</div>
            ) : null}
            {headerWidgets}
          </Header>
          {/* 页签条（REQ-20261119-063129 契约③）：Header 下方自绘 pill 条；逃生口模式下不渲染 */}
          {tabBar ?? null}
          {/* 内容区视口内自滚：minHeight 0 解除 flex 子项最小尺寸约束，整页高度不随内容撑大。
              内容包裹层 .content-body 承载内容宽度档位（REQ-20261121-043502 确认⑥）：
              fluid=不限宽现状；compact=定宽居中（global.css .content-body[data-width]）。 */}
          <Content ref={contentRef} style={{ padding: isMobile ? '16px 14px 60px' : '24px 30px 60px', flex: 1, minHeight: 0, overflow: 'auto' }}>
            <div className="content-body" data-width={contentWidth}>
              {content}
            </div>
          </Content>
        </Layout>
      </Layout>

      <Drawer
        placement="left"
        open={mobileNavOpen}
        onClose={onMobileNavClose}
        width={260}
        closable={false}
        styles={{ body: { padding: 0 } }}
      >
        {renderSiderNav(true)}
      </Drawer>
    </>
  );
}
