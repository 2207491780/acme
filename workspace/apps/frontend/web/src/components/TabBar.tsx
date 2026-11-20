import { memo, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import {
  CloseOutlined,
  LeftOutlined,
  PushpinFilled,
  PushpinOutlined,
  ReloadOutlined,
  RightOutlined,
} from '@ant-design/icons';
import { Dropdown } from 'antd';
import type { MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { findPageRoute } from '../routes';
import { useTabsStore, type TabItem } from '../store/tabsStore';
import { ARROW_STEP_RATIO, edgeState, isOverflowing, wheelDeltaToScroll } from './tabbarScroll';
import { TabPreviewCard } from './TabPreviewCard';

/**
 * 自绘页签条（REQ-20261119-063129 契约③＋迭代轮次 1 契约⑨⑩；REQ-20261120-104022 滚动增强＋
 * hover 结构化预览卡）：Header 下方独立紧凑条（高 36px，VS Code/Chrome 惯例：活动指示=底边
 * 2px accent 线、hover elevated 轻提亮；样式对齐 UI-SPEC，令牌取 CSS 变量层镜像）；
 * 不使用 antd Tabs（惰性挂载与保活相反、无右键事件）。
 *
 * REQ-20261120-104022 新增（判定逻辑唯一实现在 tabbarScroll.ts 纯函数，组件只消费）：
 * - 相邻页签 1px 分隔符：纯 CSS `.tabbar-tab + .tabbar-tab::before`（不新增 DOM 节点），
 *   hover/激活签的相邻分隔符淡出（AC-1/2）；
 * - 溢出滚动：两端步进箭头（步长≈可视宽 80%，到头 disabled）＋垂直滚轮横向滚动
 *   （wheel 监听 passive:false 仅挂 .tabbar 且仅溢出时挂载；Ctrl/Cmd 放行、横向意图放行）
 *   ＋端点渐变阴影（溢出端显示，≤300ms opacity 过渡）（AC-3~7/9/17）；
 * - 溢出/到头判定 rAF 节流，布尔翻转才写 class/disabled（不进 React state，不逐帧写样式），
 *   判定不触发 React 重渲染（AC-18）；
 * - activePath 变化 → 活动签 scrollIntoView(block/inline=nearest)（AC-8）；
 * - hover 非活动签 150ms 浮出结构化预览卡（Portal 挂 body；图标＋完整标题＋刷新/固定·取消固定/
 *   关闭；permanent 无关闭/固定、pinned 显取消固定无关闭；指针离开延迟关闭；点击操作后关卡；
 *   hover 候选态只进组件本地 state 不进 zustand；出现/消失 opacity/transform 过渡且页签列表
 *   经 memo 不随之重渲染）（AC-10~15）；页签不再设置原生 title（AC-16，避免双 tooltip）。
 * 每 tab=图标(可选)＋label＋关闭钮（16px；非活动签 hover 才显、活动签常显，::after 扩展命中区；
 * pinned 显固定图标且不可关；首页常驻签 permanent 无关闭/固定钮，右键关闭/固定禁用）；
 * 右键 Dropdown 菜单：刷新/关闭/固定·取消固定/关闭其他/关闭右侧/关闭全部。
 */

/** 预览卡浮出延迟（契约 AC-10：hover 停留 ≥150ms）。 */
const PREVIEW_OPEN_DELAY_MS = 150;
/** 预览卡指针离开后的延迟关闭（AC-15：防页签与卡边缘抖动闪烁）。 */
const PREVIEW_CLOSE_DELAY_MS = 120;
/** 预览卡退出过渡时长（镜像 MOTION.base=200ms，契约 ≤300ms 上限内）。 */
const PREVIEW_EXIT_MS = 200;
/** 预览卡与页签条的间距（px）。 */
const PREVIEW_GAP_PX = 6;
/** 预览卡最大宽度（px，与 .tab-preview-layer max-width 一致；用于视口右缘钳制）。 */
const PREVIEW_MAX_WIDTH_PX = 320;

/** 页签 label 解析（契约②优先级）：动态菜单 menuName 快照 > labelKey 映射 > path。 */
export function resolveTabLabel(tab: TabItem, t: (key: string) => string): string {
  if (tab.label) return tab.label;
  const routeLabelKey = findPageRoute(tab.path)?.labelKey;
  if (tab.labelKey || routeLabelKey) return t(tab.labelKey ?? routeLabelKey ?? tab.path);
  return tab.path;
}

/** 预览卡视口内左缘钳制：优先对齐页签左缘，超出右缘时回退（下限 8px 防贴边截断）。 */
function previewLeft(rect: DOMRect): number {
  const viewport = typeof window !== 'undefined' ? window.innerWidth : 0;
  const maxLeft = viewport - PREVIEW_MAX_WIDTH_PX - 8;
  return Math.max(8, Math.min(rect.left, maxLeft));
}

function clearTimer(ref: { current: ReturnType<typeof setTimeout> | null }): void {
  if (ref.current !== null) {
    clearTimeout(ref.current);
    ref.current = null;
  }
}

/**
 * 预览卡数据现取（round 2 加固，diagnostic AC-12 归因）：pointerenter 闭包捕获的 TabItem 是
 * 快照，150ms 开卡窗口/复活显示时刻 pinned/label 可能已漂移——任何使卡片可见的时刻都按 path
 * 从 store 现取最新 TabItem；页签已关（store 中无该 path）返回 undefined，调用侧不开卡。
 */
function latestTabOf(path: string): TabItem | undefined {
  return useTabsStore.getState().tabs.find((tab) => tab.path === path);
}

/** 预览卡候选/展示态（组件本地 state，AC-15：不写入 zustand）。 */
interface TabPreviewState {
  tab: TabItem;
  /** 触发签的视口矩形（开卡时刻快照，fixed 定位用）。 */
  rect: DOMRect;
  /** 退出淡出进行中（延迟关闭的两段式：延迟→closing→卸载）。 */
  closing: boolean;
}

interface TabListProps {
  tabs: TabItem[];
  activePath: string | null;
  iconOf: (path: string) => ReactNode;
  onSelect: (path: string) => void;
  onClose: (path: string) => void;
  onRefresh: (path: string) => void;
  onTogglePin: (path: string) => void;
  onCloseOthers: (path: string) => void;
  onCloseRight: (path: string) => void;
  onCloseAll: () => void;
  schedulePreviewOpen: (tab: TabItem, rect: DOMRect) => void;
  schedulePreviewClose: () => void;
}

/**
 * 页签列表（memo 化子树）：预览卡浮出/关闭只改 TabBar 本地 state，本子树 props 不变即整体
 * 跳过重渲染（性能红线：出现/消失不触发页签列表 re-render）。
 */
const TabList = memo(function TabList({
  tabs,
  activePath,
  iconOf,
  onSelect,
  onClose,
  onRefresh,
  onTogglePin,
  onCloseOthers,
  onCloseRight,
  onCloseAll,
  schedulePreviewOpen,
  schedulePreviewClose,
}: TabListProps) {
  const { t } = useTranslation();

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
    <>
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
              onClick={() => onSelect(tab.path)}
              // hover 预览卡（AC-10/14）：仅非活动签进入 150ms 候选；原生 title 已移除（AC-16）
              onPointerEnter={active ? undefined : (event) => schedulePreviewOpen(tab, event.currentTarget.getBoundingClientRect())}
              onPointerLeave={schedulePreviewClose}
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
    </>
  );
});

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

  // —— DOM 引用：滚动容器/两端箭头/两端阴影（箭头与阴影 DOM 常驻，显隐走 class，见 sync） ——
  const containerRef = useRef<HTMLDivElement | null>(null);
  const leftArrowRef = useRef<HTMLButtonElement | null>(null);
  const rightArrowRef = useRef<HTMLButtonElement | null>(null);
  const leftShadowRef = useRef<HTMLSpanElement | null>(null);
  const rightShadowRef = useRef<HTMLSpanElement | null>(null);

  // —— 滚动判定（AC-18）：rAF 节流 + 布尔翻转才写 class/disabled，全部不进 React state ——
  const overflowingRef = useRef(false);
  const rafRef = useRef<number | null>(null);
  // latest-ref：scheduleSync 经 rAF 间接调用最新一次渲染的 syncTabbar（打破 useCallback 环）
  const syncRef = useRef<() => void>(() => undefined);

  const scheduleSync = useCallback(() => {
    if (rafRef.current !== null) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null;
      syncRef.current();
    });
  }, []);

  // wheel 劫持（AC-4/5/9）：纯垂直滚轮意图才映射为横向滚动；监听仅溢出时挂载（见 sync）
  const handleWheel = useCallback((event: WheelEvent) => {
    const el = containerRef.current;
    if (!el) return;
    const delta = wheelDeltaToScroll(event.deltaMode, event.deltaX, event.deltaY, event.ctrlKey, event.metaKey);
    if (delta === null) return; // Ctrl/Cmd（页面缩放）与横向意图（触控板双指横滑）放行
    event.preventDefault();
    el.scrollLeft += delta;
    scheduleSync();
  }, [scheduleSync]);

  // 溢出/到头判定 → 类名与 disabled 翻转（仅布尔翻转时写；判定不触发 React 重渲染）
  const syncTabbar = () => {
    const el = containerRef.current;
    if (!el) return;
    const overflowing = isOverflowing(el.scrollWidth, el.clientWidth);
    if (overflowing !== overflowingRef.current) {
      overflowingRef.current = overflowing;
      el.classList.toggle('tabbar-overflowing', overflowing);
      // wheel 监听 passive:false 仅挂 .tabbar 且仅溢出时挂载（性能红线）
      if (overflowing) el.addEventListener('wheel', handleWheel, { passive: false });
      else el.removeEventListener('wheel', handleWheel);
    }
    const edge = overflowing
      ? edgeState(el.scrollLeft, el.clientWidth, el.scrollWidth)
      : { canLeft: false, canRight: false };
    const leftArrow = leftArrowRef.current;
    if (leftArrow && leftArrow.disabled !== !edge.canLeft) leftArrow.disabled = !edge.canLeft;
    const rightArrow = rightArrowRef.current;
    if (rightArrow && rightArrow.disabled !== !edge.canRight) rightArrow.disabled = !edge.canRight;
    // 端点阴影：溢出且该端有未显示内容才显示（AC-17）；class 翻转驱动 ≤300ms opacity 过渡
    leftShadowRef.current?.classList.toggle('tabbar-shadow-visible', overflowing && edge.canLeft);
    rightShadowRef.current?.classList.toggle('tabbar-shadow-visible', overflowing && edge.canRight);
  };
  useEffect(() => {
    syncRef.current = syncTabbar;
  });

  // —— 预览卡候选/展示（组件本地 state，AC-15 不进 zustand；AC-13 点击操作后关卡） ——
  const [preview, setPreview] = useState<TabPreviewState | null>(null);
  const previewRef = useRef<TabPreviewState | null>(null);
  const openTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const closeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const applyPreview = useCallback((next: TabPreviewState | null) => {
    previewRef.current = next;
    setPreview(next);
  }, []);

  // hover 进入候选（AC-10）：150ms 后浮出；同签已展示则取消退出淡出即可。
  // 开卡数据现取（round 2 加固）：定时器触发点按 path 从 store 现取最新 TabItem
  // （pointerenter 闭包快照在 pinned/label 漂移场景陈旧）；页签已关则不开卡。
  const schedulePreviewOpen = useCallback((tab: TabItem, rect: DOMRect) => {
    clearTimer(closeTimerRef);
    clearTimer(openTimerRef);
    const current = previewRef.current;
    if (current && current.tab.path === tab.path) {
      if (current.closing) {
        const resumed = latestTabOf(tab.path);
        if (!resumed) return; // 页签已关：不复活，退出淡出继续至卸载
        applyPreview({ ...current, tab: resumed, closing: false });
      }
      return;
    }
    openTimerRef.current = setTimeout(() => {
      openTimerRef.current = null;
      const latest = latestTabOf(tab.path);
      if (!latest) return; // 页签已关：不开卡
      applyPreview({ tab: latest, rect, closing: false });
    }, PREVIEW_OPEN_DELAY_MS);
  }, [applyPreview]);

  // 延迟关闭（AC-15）：指针离开页签与卡后延迟关 → 退出淡出 → 卸载（防边缘抖动闪烁）
  const schedulePreviewClose = useCallback(() => {
    clearTimer(openTimerRef);
    const current = previewRef.current;
    if (!current || current.closing) return;
    if (closeTimerRef.current !== null) return;
    closeTimerRef.current = setTimeout(() => {
      closeTimerRef.current = null;
      const leaving = previewRef.current;
      if (!leaving || leaving.closing) return;
      applyPreview({ ...leaving, closing: true });
      closeTimerRef.current = setTimeout(() => {
        closeTimerRef.current = null;
        if (previewRef.current?.closing) applyPreview(null);
      }, PREVIEW_EXIT_MS);
    }, PREVIEW_CLOSE_DELAY_MS);
  }, [applyPreview]);

  // 指针回到页签/卡上：取消关闭并撤销退出淡出（数据同取最新；页签已关则放任淡出至卸载）
  const keepPreview = useCallback(() => {
    clearTimer(closeTimerRef);
    const current = previewRef.current;
    if (current?.closing) {
      const resumed = latestTabOf(current.tab.path);
      if (!resumed) return;
      applyPreview({ ...current, tab: resumed, closing: false });
    }
  }, [applyPreview]);

  // 立即关闭（点击卡内操作后 AC-13 / 活动签变化时）
  const closePreviewNow = useCallback(() => {
    clearTimer(openTimerRef);
    clearTimer(closeTimerRef);
    applyPreview(null);
  }, [applyPreview]);

  // 箭头步进（AC-7）：步长 ≈ 可视宽 80%；到头兜底无效（disabled 属性之外的纯函数判定）
  const handleArrowClick = (direction: 'left' | 'right') => () => {
    const el = containerRef.current;
    if (!el) return;
    const edge = edgeState(el.scrollLeft, el.clientWidth, el.scrollWidth);
    if (direction === 'left' ? !edge.canLeft : !edge.canRight) return;
    const step = Math.max(0, el.clientWidth) * ARROW_STEP_RATIO;
    el.scrollLeft += direction === 'left' ? -step : step;
    scheduleSync();
  };

  // 溢出态随页签增删/标签变化重判（AC-4：溢出才显示箭头/阴影并挂 wheel）
  useEffect(() => {
    scheduleSync();
  }, [tabs, scheduleSync]);

  // 容器尺寸变化（侧栏折叠/窗口缩放）→ 重判溢出
  useEffect(() => {
    const el = containerRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(() => scheduleSync());
    observer.observe(el);
    return () => observer.disconnect();
  }, [scheduleSync]);

  // activePath 变化（AC-8）：活动签就近滚入可视区（不强制居中）；若被 hover 的签变为活动签则关卡（AC-14）
  useEffect(() => {
    if (!activePath) return;
    const current = previewRef.current;
    if (current && current.tab.path === activePath) closePreviewNow();
    const activeTab = containerRef.current?.querySelector<HTMLElement>(`.tabbar-tab[data-tab-path="${activePath}"]`);
    // jsdom 未实现 scrollIntoView：?. 调用防崩；真实浏览器按 block/inline=nearest 就近滚入
    activeTab?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
    scheduleSync();
  }, [activePath, scheduleSync, closePreviewNow]);

  // 卸载清理：pending rAF / wheel 监听 / 预览卡计时器
  useEffect(() => {
    const el = containerRef.current;
    return () => {
      if (el) el.removeEventListener('wheel', handleWheel);
    };
  }, [handleWheel]);
  useEffect(() => () => {
    if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
    clearTimer(openTimerRef);
    clearTimer(closeTimerRef);
  }, []);

  return (
    <div className="tabbar-wrap">
      <div
        ref={containerRef}
        className="tabbar"
        role="tablist"
        aria-label={t('nav.tabs.label')}
        onScroll={() => {
          scheduleSync();
          schedulePreviewClose(); // 滚动即弃卡（fixed 定位卡不随签漂移，防悬挂错位）
        }}
      >
        <TabList
          tabs={tabs}
          activePath={activePath}
          iconOf={iconOf}
          onSelect={onSelect}
          onClose={onClose}
          onRefresh={onRefresh}
          onTogglePin={onTogglePin}
          onCloseOthers={onCloseOthers}
          onCloseRight={onCloseRight}
          onCloseAll={onCloseAll}
          schedulePreviewOpen={schedulePreviewOpen}
          schedulePreviewClose={schedulePreviewClose}
        />
      </div>
      {/* 端点渐变阴影（AC-17）＋步进箭头（AC-4/6/7）：DOM 常驻、显隐/禁用由 rAF 判定翻转（AC-18） */}
      <span ref={leftShadowRef} className="tabbar-shadow tabbar-shadow-left" aria-hidden="true" />
      <span ref={rightShadowRef} className="tabbar-shadow tabbar-shadow-right" aria-hidden="true" />
      <button
        ref={leftArrowRef}
        type="button"
        className="tabbar-arrow tabbar-arrow-left"
        aria-label={t('nav.tabs.scrollStart')}
        onClick={handleArrowClick('left')}
      >
        <LeftOutlined />
      </button>
      <button
        ref={rightArrowRef}
        type="button"
        className="tabbar-arrow tabbar-arrow-right"
        aria-label={t('nav.tabs.scrollEnd')}
        onClick={handleArrowClick('right')}
      >
        <RightOutlined />
      </button>
      {/* 预览卡（AC-10）：Portal 挂 body；弹层容器承担 fixed 定位与出现/消失 opacity/transform 过渡 */}
      {preview
        ? createPortal(
          <div
            className={preview.closing ? 'tab-preview-layer tab-preview-closing' : 'tab-preview-layer'}
            style={{ left: previewLeft(preview.rect), top: preview.rect.bottom + PREVIEW_GAP_PX }}
            onPointerEnter={keepPreview}
            onPointerLeave={schedulePreviewClose}
          >
            <TabPreviewCard
              tab={preview.tab}
              icon={iconOf(preview.tab.path)}
              label={resolveTabLabel(preview.tab, t)}
              onRefresh={(path) => {
                onRefresh(path);
                closePreviewNow();
              }}
              onTogglePin={(path) => {
                onTogglePin(path);
                closePreviewNow();
              }}
              onClose={(path) => {
                onClose(path);
                closePreviewNow();
              }}
            />
          </div>,
          document.body,
        )
        : null}
    </div>
  );
}
