import { Suspense } from 'react';
import { Skeleton } from 'antd';
import { useTranslation } from 'react-i18next';
import { findPageRoute } from '../routes';
import { useTabsStore } from '../store/tabsStore';
import { PageBoundary, PermissionGate } from './PermissionGate';

/**
 * 多页签保活容器（REQ-20261119-063129 契约③④）：按 tabsStore 已开页签逐 tab 渲染容器 div
 * （非激活 `display:none`），页面挂载一次、切换不卸载；PageBoundary＋PermissionGate 逐 tab
 * 包裹（每 tab 独立 Suspense，lazy chunk 首次进入该页签才加载）。
 *
 * 刷新语义：refreshKeys[path] 计入容器 React key → 右键刷新 key++ 强制重挂该页子树（真实刷新）；
 * 数据策略=保持现状（页面手动刷新按钮已有），激活不自动重取（useCrud 双守卫保活不误伤）。
 */
export function TabKeepAlive() {
  const { t } = useTranslation();
  const tabs = useTabsStore((s) => s.tabs);
  const activePath = useTabsStore((s) => s.activePath);
  const refreshKeys = useTabsStore((s) => s.refreshKeys);

  return (
    <>
      {tabs.map((tab) => {
        const route = findPageRoute(tab.path);
        if (!route) return null; // 已下线路由的残留页签：不渲染（TabBar 侧同样过滤）
        const active = tab.path === activePath;
        const refreshKey = refreshKeys[tab.path] ?? 0;
        return (
          <div
            key={`${tab.path}#${refreshKey}`}
            data-tab-path={tab.path}
            data-tab-active={active ? 'true' : 'false'}
            role="tabpanel"
            aria-label={t(route.labelKey)}
            hidden={!active}
            style={{ display: active ? undefined : 'none' }}
          >
            <Suspense fallback={<Skeleton active title paragraph={{ rows: 6 }} style={{ marginTop: 8 }} />}>
              <PageBoundary>
                {route.permission
                  ? <PermissionGate permission={route.permission}>{route.element}</PermissionGate>
                  : route.element}
              </PageBoundary>
            </Suspense>
          </div>
        );
      })}
    </>
  );
}
