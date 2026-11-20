import type { ReactNode } from 'react';
import { CloseOutlined, PushpinFilled, PushpinOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { TabItem } from '../store/tabsStore';

/**
 * 页签 hover 结构化预览卡（REQ-20261120-104022 AC-10~14）：非真截图，渲染=页签图标＋完整标题
 * （不受 .tabbar-label 160px 截断）＋快捷操作钮组（刷新/固定·取消固定/关闭）。
 *
 * 语义与右键菜单一致（AC-13）：permanent 首页常驻签无关闭钮/无固定钮（AC-11，与右键菜单
 * 禁用语义一致——直接不渲染）；pinned 签固定钮显示「取消固定」且无关闭钮（AC-12，pinned 不可关）。
 * 显示/隐藏与定位由 TabBar 承担（Portal 挂 body 的弹层容器在父侧，含出现/消失过渡），
 * 本组件只按契约 props 渲染内容；操作回调复用右键菜单同源 handler（onRefresh/onTogglePin/onClose）。
 */
export interface TabPreviewCardProps {
  tab: TabItem;
  /** 页签图标（与页签条同源：动态菜单 icon 优先，静态 NAV 兜底）。 */
  icon: ReactNode;
  /** 完整标题（resolveTabLabel 解析结果，不经 160px 截断）。 */
  label: string;
  onRefresh(path: string): void;
  onTogglePin(path: string): void;
  onClose(path: string): void;
}

export function TabPreviewCard({ tab, icon, label, onRefresh, onTogglePin, onClose }: TabPreviewCardProps) {
  const { t } = useTranslation();
  const permanent = tab.permanent === true;
  const pinned = tab.pinned === true;
  const act = (action: (path: string) => void) => () => action(tab.path);

  return (
    <div className="tab-preview-card">
      <div className="tab-preview-head">
        {icon ? <span className="tab-preview-icon">{icon}</span> : null}
        <span className="tab-preview-label">{label}</span>
      </div>
      <div className="tab-preview-actions">
        <button type="button" className="tab-preview-action" aria-label={t('nav.tabs.refresh')} onClick={act(onRefresh)}>
          <ReloadOutlined />
          <span>{t('nav.tabs.refresh')}</span>
        </button>
        {/* permanent 无固定钮（AC-11：固定/取消固定对常驻首页签无意义，与右键菜单禁用语义一致） */}
        {!permanent && (
          <button
            type="button"
            className="tab-preview-action"
            aria-label={pinned ? t('nav.tabs.unpin') : t('nav.tabs.pin')}
            onClick={act(onTogglePin)}
          >
            {pinned ? <PushpinFilled /> : <PushpinOutlined />}
            <span>{pinned ? t('nav.tabs.unpin') : t('nav.tabs.pin')}</span>
          </button>
        )}
        {/* permanent 与 pinned 均不可关（AC-11/AC-12）：无关闭钮 */}
        {!permanent && !pinned && (
          <button type="button" className="tab-preview-action" aria-label={t('nav.tabs.close')} onClick={act(onClose)}>
            <CloseOutlined />
            <span>{t('nav.tabs.close')}</span>
          </button>
        )}
      </div>
    </div>
  );
}
