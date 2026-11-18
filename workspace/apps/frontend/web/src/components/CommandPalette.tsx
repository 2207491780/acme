import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Modal, Input } from 'antd';
import type { InputRef } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PAGE_ROUTES } from '../routes';
import { useTabsStore } from '../store/tabsStore';
import { resolveTabLabel } from './TabBar';

/**
 * 全局命令面板（REQ-20261119-063129 契约⑤）：Ctrl+K 开 antd Modal（顶部 20% 定位），
 * 输入过滤三节——页面（PAGE_ROUTES+label 含 /files）/页签（已开 tabs）/快捷操作
 * （锁屏/切换主题/切换语言/新建发布单/退出登录，动作经 App 既有 handlers 注入）；
 * 键盘 ↑↓ 选择＋Enter 执行＋Esc 关闭；执行页面=「打开为页签」（navigate 后由 location
 * 同步 effect 开签）。焦点管理：开面板 autofocus 输入、关面板还原既有焦点。
 */

interface CommandItem {
  key: string;
  section: 'pages' | 'tabs' | 'actions';
  label: string;
  /** 次要匹配面（path / 已开页签锚点）。 */
  hint?: string;
  run: () => void;
}

export interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
  onOpenPage: (path: string) => void;
  onLock: () => void;
  onToggleTheme: () => void;
  onToggleLanguage: () => void;
  onCreateRelease: () => void;
  onLogout: () => void;
}

const SECTION_ORDER: CommandItem['section'][] = ['pages', 'tabs', 'actions'];

export function CommandPalette({
  open, onClose, onOpenPage, onLock, onToggleTheme, onToggleLanguage, onCreateRelease, onLogout,
}: CommandPaletteProps) {
  const { t } = useTranslation();
  const tabs = useTabsStore((s) => s.tabs);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<InputRef>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const lastActiveRef = useRef<HTMLElement | null>(null);

  // 开面板：重置查询/选中、autofocus 输入；关面板：还原既有焦点（契约⑤焦点管理）
  useEffect(() => {
    if (open) {
      lastActiveRef.current = document.activeElement as HTMLElement | null;
      setQuery('');
      setActiveIndex(0);
      const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
      return () => window.clearTimeout(timer);
    }
    lastActiveRef.current?.focus?.();
    return undefined;
  }, [open]);

  const items = useMemo<CommandItem[]>(() => {
    const pages: CommandItem[] = PAGE_ROUTES.map((route) => ({
      key: `page:${route.path}`,
      section: 'pages' as const,
      label: t(route.labelKey),
      hint: route.path,
      run: () => onOpenPage(route.path),
    }));
    const openTabs: CommandItem[] = tabs.map((tab) => ({
      key: `tab:${tab.path}`,
      section: 'tabs' as const,
      label: resolveTabLabel(tab, t),
      hint: tab.path,
      run: () => onOpenPage(tab.path),
    }));
    const actions: CommandItem[] = [
      { key: 'action:lock', section: 'actions', label: t('nav.header.lockScreen'), run: onLock },
      { key: 'action:theme', section: 'actions', label: t('nav.header.toggleTheme'), run: onToggleTheme },
      { key: 'action:language', section: 'actions', label: t('nav.header.language'), run: onToggleLanguage },
      { key: 'action:createRelease', section: 'actions', label: t('nav.commandPalette.createRelease'), run: onCreateRelease },
      { key: 'action:logout', section: 'actions', label: t('nav.userMenu.logout'), run: onLogout },
    ];
    const all = [...pages, ...openTabs, ...actions];
    const keyword = query.trim().toLowerCase();
    if (!keyword) return all;
    return all.filter((item) =>
      item.label.toLowerCase().includes(keyword)
      || (item.hint?.toLowerCase().includes(keyword) ?? false));
  }, [t, tabs, query, onOpenPage, onLock, onToggleTheme, onToggleLanguage, onCreateRelease, onLogout]);

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
    item.run();
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

  const sectionTitle = (section: CommandItem['section']): string => t(`nav.commandPalette.sections.${section}`);

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
            if (sectionItems.length === 0) return null;
            return (
              <div key={section}>
                <div className="command-palette-section">{sectionTitle(section)}</div>
                {sectionItems.map(({ item, index }) => (
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
                ))}
              </div>
            );
          })
        )}
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
