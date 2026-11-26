import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Modal, Input } from 'antd';
import type { InputRef } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PAGE_ROUTES } from '../routes';
import { useTabsStore } from '../store/tabsStore';
import { resolveTabLabel } from './TabBar';
import {
  clearCommandHistory,
  readCommandHistory,
  recordCommandHistory,
  type CommandHistoryEntry,
} from './commandHistory';

/**
 * 全局命令面板（REQ-20261119-063129 契约⑤；REQ-20261120-120552 增强）：
 * Ctrl+K 开 antd Modal（顶部 20% 定位）；输入过滤三节——页面（PAGE_ROUTES+label 含 /files，
 * 按注入 hasPermission 过滤：与侧边导航同源 fail-closed，无 permission 路由恒可见）/
 * 页签（已开 tabs）/快捷操作（锁屏/切换主题/切换语言/退出登录，动作经 App 既有
 * handlers 注入）；空查询另渲染「搜索历史」节（acme.commandHistory.v1，最近 8 条，空态
 * 「没有搜索历史」，头部「清空」钮仅非空显示）；键盘 ↑↓ 选择＋Enter 执行＋Esc 关闭；
 * 执行页面/动作记入搜索历史（页签不记）；底部键位提示条「↵ 选择 / ↑↓ 导航 / ESC 关闭」。
 * 焦点管理：开面板 autofocus 输入、关面板还原既有焦点。
 */

interface CommandItem {
  key: string;
  section: 'history' | 'pages' | 'tabs' | 'actions';
  label: string;
  /** 次要匹配面（path / 已开页签锚点）。 */
  hint?: string;
  run: () => void;
  /** 执行时记入搜索历史的底层命令（页面/动作条目携带；页签/历史条目经此回记）。 */
  record?: { kind: CommandHistoryEntry['kind']; key: string; label: string };
}

export interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
  onOpenPage: (path: string) => void;
  onLock: () => void;
  onToggleTheme: () => void;
  onToggleLanguage: () => void;
  onLogout: () => void;
  /** 「页面」节权限过滤（REQ-20261120-120552 AC-10）：与侧边导航同源（App 既有权限集），
   *  fail-closed；code 缺省（无 permission 路由）恒 true。 */
  hasPermission: (code?: string) => boolean;
}

const SECTION_ORDER: CommandItem['section'][] = ['history', 'pages', 'tabs', 'actions'];

export function CommandPalette({
  open, onClose, onOpenPage, onLock, onToggleTheme, onToggleLanguage, onLogout,
  hasPermission,
}: CommandPaletteProps) {
  const { t } = useTranslation();
  const tabs = useTabsStore((s) => s.tabs);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  // 搜索历史（REQ-20261120-120552）：开面板时读存储；执行条目后以 recordCommandHistory 返回值刷新
  const [history, setHistory] = useState<CommandHistoryEntry[]>([]);
  const inputRef = useRef<InputRef>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const lastActiveRef = useRef<HTMLElement | null>(null);

  // 开面板：重置查询/选中、载入持久化历史、autofocus 输入；关面板：还原既有焦点（契约⑤焦点管理）
  useEffect(() => {
    if (open) {
      lastActiveRef.current = document.activeElement as HTMLElement | null;
      setQuery('');
      setActiveIndex(0);
      setHistory(readCommandHistory());
      const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
      return () => window.clearTimeout(timer);
    }
    lastActiveRef.current?.focus?.();
    return undefined;
  }, [open]);

  const items = useMemo<CommandItem[]>(() => {
    // 「页面」节权限过滤（AC-10）：与侧边导航 permittedNav 同源口径（me.permissions，fail-closed）
    const pages: CommandItem[] = PAGE_ROUTES
      .filter((route) => hasPermission(route.permission))
      .map((route) => ({
        key: `page:${route.path}`,
        section: 'pages' as const,
        label: t(route.labelKey),
        hint: route.path,
        run: () => onOpenPage(route.path),
        record: { kind: 'page' as const, key: `page:${route.path}`, label: t(route.labelKey) },
      }));
    const openTabs: CommandItem[] = tabs.map((tab) => ({
      key: `tab:${tab.path}`,
      section: 'tabs' as const,
      label: resolveTabLabel(tab, t),
      hint: tab.path,
      run: () => onOpenPage(tab.path),
    }));
    const actionDefs: Omit<CommandItem, 'record'>[] = [
      { key: 'action:lock', section: 'actions', label: t('nav.header.lockScreen'), run: onLock },
      { key: 'action:theme', section: 'actions', label: t('nav.header.toggleTheme'), run: onToggleTheme },
      { key: 'action:language', section: 'actions', label: t('nav.header.language'), run: onToggleLanguage },
      { key: 'action:logout', section: 'actions', label: t('nav.userMenu.logout'), run: onLogout },
    ];
    const actions: CommandItem[] = actionDefs.map((item) => ({
      ...item,
      record: { kind: 'action' as const, key: item.key, label: item.label },
    }));
    const keyword = query.trim().toLowerCase();
    // 空查询：历史条目置顶（回放执行）；关键词过滤仅作用于页面/页签/快捷操作三节
    const resolvedHistory: CommandItem[] = keyword
      ? []
      : history
        .map((entry): CommandItem | null => {
          const match = [...pages, ...actions]
            .find((item) => item.record !== undefined && item.record.key === entry.key && item.record.kind === entry.kind);
          if (!match || !match.record) return null; // 失效条目（路由移除/权限收回）不渲染，fail-closed
          return {
            key: `history:${entry.key}`,
            section: 'history',
            label: entry.label,
            hint: match.hint,
            run: match.run,
            record: { kind: entry.kind, key: entry.key, label: match.record.label },
          };
        })
        .filter((item): item is CommandItem => item !== null);
    const all = [...resolvedHistory, ...pages, ...openTabs, ...actions];
    if (!keyword) return all;
    return all.filter((item) =>
      item.section !== 'history'
      && (item.label.toLowerCase().includes(keyword)
        || (item.hint?.toLowerCase().includes(keyword) ?? false)));
  }, [t, tabs, query, history, hasPermission, onOpenPage, onLock, onToggleTheme, onToggleLanguage, onLogout]);

  // 列表随查询变化：选中项收敛到有效区间
  useEffect(() => {
    setActiveIndex((index) => (items.length === 0 ? 0 : Math.min(index, items.length - 1)));
  }, [items.length]);

  // 选中项保持可见（键盘导航）；scrollIntoView 逐环境可用性不一（jsdom 未实现），守卫调用
  useEffect(() => {
    const activeEl = listRef.current?.querySelector('[data-active="true"]');
    if (typeof activeEl?.scrollIntoView === 'function') {
      activeEl.scrollIntoView({ block: 'nearest' });
    }
  }, [activeIndex, items.length, open]);

  const runItem = (item: CommandItem | undefined) => {
    if (!item) return;
    // 执行页面/动作 → 记入搜索历史（REQ-20261120-120552 AC-7；去重置顶截断由模块裁决）
    if (item.record) {
      setHistory(recordCommandHistory(item.record));
    }
    item.run();
  };

  const handleClearHistory = () => {
    clearCommandHistory();
    setHistory([]);
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (items.length > 0) setActiveIndex((index) => (index + 1) % items.length);
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (items.length > 0) setActiveIndex((index) => (index - 1 + items.length) % items.length);
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      runItem(items[activeIndex]);
      return;
    }
    // Esc 交由 Modal keyboard 关闭（契约⑤）
  };

  const sectionTitle = (section: CommandItem['section']): string =>
    section === 'history'
      ? t('nav.commandPalette.history')
      : t(`nav.commandPalette.sections.${section}`);

  const renderItemButton = ({ item, index }: { item: CommandItem; index: number }): ReactNode => (
    <button
      key={item.key}
      type="button"
      className="command-palette-item"
      data-active={index === activeIndex ? 'true' : 'false'}
      onMouseEnter={() => setActiveIndex(index)}
      onClick={() => runItem(item)}
    >
      <span className="command-palette-item-label">{item.label}</span>
      {item.hint ? <span className="command-palette-item-hint">{item.hint}</span> : null}
    </button>
  );

  const body: ReactNode = (
    <div onKeyDown={onKeyDown}>
      <Input
        ref={inputRef}
        allowClear
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder={t('nav.commandPalette.placeholder')}
        prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
        aria-label={t('nav.commandPalette.open')}
      />
      <div ref={listRef} className="command-palette-list" style={{ marginTop: 12, maxHeight: 320, overflowY: 'auto' }}>
        {items.length === 0 ? (
          <div style={{ padding: '16px 8px', textAlign: 'center', color: 'var(--muted)', fontSize: 13 }}>
            {t('nav.commandPalette.empty')}
          </div>
        ) : (
          SECTION_ORDER.map((section) => {
            const sectionItems = items
              .map((item, index) => ({ item, index }))
              .filter(({ item }) => item.section === section);
            if (section === 'history') {
              // 搜索历史节（REQ-20261120-120552 AC-6/8）：仅空查询渲染；头部「清空」钮仅非空显示；
              // 无历史渲染空态「没有搜索历史」（对齐截图）
              if (query.trim() !== '') return null;
              return (
                <div key={section}>
                  <div className="command-palette-section command-palette-history-head">
                    <span>{sectionTitle(section)}</span>
                    {history.length > 0 ? (
                      <button
                        type="button"
                        className="command-palette-clear"
                        onClick={handleClearHistory}
                      >
                        {t('nav.commandPalette.clearHistory')}
                      </button>
                    ) : null}
                  </div>
                  {history.length === 0 ? (
                    <div className="command-palette-nohistory">{t('nav.commandPalette.noHistory')}</div>
                  ) : (
                    sectionItems.map(renderItemButton)
                  )}
                </div>
              );
            }
            if (sectionItems.length === 0) return null;
            return (
              <div key={section}>
                <div className="command-palette-section">{sectionTitle(section)}</div>
                {sectionItems.map(renderItemButton)}
              </div>
            );
          })
        )}
      </div>
      {/* 底部键位提示条（REQ-20261120-120552 AC-11）：「↵ 选择 / ↑↓ 导航 / ESC 关闭」 */}
      <div className="command-palette-hints">
        <span><kbd>↵</kbd> {t('nav.commandPalette.hints.select')}</span>
        <span><kbd>↑↓</kbd> {t('nav.commandPalette.hints.navigate')}</span>
        <span><kbd>ESC</kbd> {t('nav.commandPalette.hints.close')}</span>
      </div>
    </div>
  );

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      title={null}
      closable={false}
      width={520}
      style={{ top: '20vh' }}
      styles={{ body: { padding: 12 } }}
    >
      {body}
    </Modal>
  );
}
