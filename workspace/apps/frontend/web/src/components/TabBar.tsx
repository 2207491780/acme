import type { ReactNode } from 'react';
import { CloseOutlined, PushpinFilled, PushpinOutlined, ReloadOutlined } from '@ant-design/icons';
import { Dropdown } from 'antd';
import type { MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { findPageRoute } from '../routes';
import { useTabsStore, type TabItem } from '../store/tabsStore';

/**
 * 自绘页签条（REQ-20261119-063129 契约③＋迭代轮次 1 契约⑨⑩）：Header 下方独立紧凑条
 * （高 36px，VS Code/Chrome 惯例：活动指示=底边 2px accent 线、hover elevated 轻提亮；
 * 样式对齐 UI-SPEC，令牌取 CSS 变量层镜像）；不使用 antd Tabs（惰性挂载与保活相反、无右键事件）。
 * 每 tab=图标(可选)＋label＋关闭钮（16px；非活动签 hover 才显、活动签常显，::after 扩展命中区；
 * pinned 显固定图标且不可关；首页常驻签 permanent 无关闭/固定钮，右键关闭/固定禁用）；
 * 右键 Dropdown 菜单：刷新/关闭/固定·取消固定/关闭其他/关闭右侧/关闭全部。
 */

/** 页签 label 解析（契约②优先级）：动态菜单 menuName 快照 > labelKey 映射 > path。 */
export function resolveTabLabel(tab: TabItem, t: (key: string) => string): string {
  if (tab.label) return tab.label;
  const routeLabelKey = findPageRoute(tab.path)?.labelKey;
  if (tab.labelKey || routeLabelKey) return t(tab.labelKey ?? routeLabelKey ?? tab.path);
  return tab.path;
}

export interface TabBarProps {
  /** path → 图标（动态菜单 icon 优先，静态 NAV 兜底；不可序列化故由 App 渲染时注入）。 */
  iconOf: (path: string) => ReactNode;
  onSelect: (path: string) => void;
  onClose: (path: string) => void;
  onRefresh: (path: string) => void;
  onTogglePin: (path: string) => void;
  onCloseOthers: (path: string) => void;
  onCloseRight: (path: string) => void;
  onCloseAll: () => void;
}

export function TabBar({ iconOf, onSelect, onClose, onRefresh, onTogglePin, onCloseOthers, onCloseRight, onCloseAll }: TabBarProps) {
  const { t } = useTranslation();
  const tabs = useTabsStore((s) => s.tabs);
  const activePath = useTabsStore((s) => s.activePath);

  const contextMenu = (tab: TabItem): MenuProps => ({
    items: [
      { key: 'refresh', icon: <ReloadOutlined />, label: t('nav.tabs.refresh') },
      // permanent（首页常驻）与 pinned 均不可关（契约③⑩）；permanent 固定/取消固定无意义
      { key: 'close', icon: <CloseOutlined />, label: t('nav.tabs.close'), disabled: tab.pinned || tab.permanent },
      {
        key: 'pin',
        icon: tab.pinned ? <PushpinFilled /> : <PushpinOutlined />,
        label: tab.pinned ? t('nav.tabs.unpin') : t('nav.tabs.pin'),
        disabled: tab.permanent,
      },
      { key: 'closeOthers', label: t('nav.tabs.closeOthers') },
      { key: 'closeRight', label: t('nav.tabs.closeRight') },
      { type: 'divider' },
      { key: 'closeAll', label: t('nav.tabs.closeAll') },
    ],
    onClick: ({ key, domEvent }) => {
      domEvent.stopPropagation();
      if (key === 'refresh') onRefresh(tab.path);
      if (key === 'close') onClose(tab.path);
      if (key === 'pin') onTogglePin(tab.path);
      if (key === 'closeOthers') onCloseOthers(tab.path);
      if (key === 'closeRight') onCloseRight(tab.path);
      if (key === 'closeAll') onCloseAll();
    },
  });

  return (
    <div className="tabbar" role="tablist" aria-label={t('nav.tabs.label')}>
      {tabs.map((tab) => {
        if (!findPageRoute(tab.path)) return null;
        const label = resolveTabLabel(tab, t);
        const active = tab.path === activePath;
        return (
          <Dropdown key={tab.path} trigger={['contextMenu']} menu={contextMenu(tab)}>
            <div
              className={active ? 'tabbar-tab tabbar-tab-active' : 'tabbar-tab'}
              role="tab"
              aria-selected={active}
              aria-label={label}
              data-tab-path={tab.path}
              title={label}
              onClick={() => onSelect(tab.path)}
            >
              {iconOf(tab.path)}
              <span className="tabbar-label">{label}</span>
              {tab.permanent ? null : tab.pinned ? (
                <button
                  type="button"
                  className="tabbar-pin"
                  aria-label={t('nav.tabs.unpin')}
                  onClick={(event) => {
                    event.stopPropagation();
                    onTogglePin(tab.path);
                  }}
                >
                  <PushpinFilled />
                </button>
              ) : (
                <button
                  type="button"
                  className="tabbar-close"
                  aria-label={t('nav.tabs.close')}
                  onClick={(event) => {
                    event.stopPropagation();
                    onClose(tab.path);
                  }}
                >
                  <CloseOutlined />
                </button>
              )}
            </div>
          </Dropdown>
        );
      })}
    </div>
  );
}
