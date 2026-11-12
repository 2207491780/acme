import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Input, Tag, Typography } from 'antd';
import type { InputRef } from 'antd';
import {
  AppstoreOutlined,
  DashboardOutlined,
  DownOutlined,
  ExperimentOutlined,
  ExportOutlined,
  PlusOutlined,
  SettingOutlined,
  SunOutlined,
  UnorderedListOutlined,
  UpOutlined,
} from '@ant-design/icons';
import { ACCENT_ORDER } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PROTO_REGISTRY } from '../registry';

const { Text } = Typography;

interface Command {
  id: string;
  label: string;
  group: '导航' | '原型' | '动作';
  icon: React.ReactNode;
  hint?: string;
  run: () => void;
}

const OPEN_EVENT = 'acme:open-command-palette';

/** F2 全局命令面板：Ctrl/⌘+K 唤起（capture 阶段拦截，避免与正式页聚焦搜索冲突）。
 *  成熟化时迁移到正式 App.tsx，替换现有「仅聚焦搜索」行为。 */
export default function CommandPalette() {
  const navigate = useNavigate();
  const dispatch = useStore((s) => s.dispatch);
  const openModal = useStore((s) => s.openModal);
  const openDrawer = useStore((s) => s.openDrawer);
  const settings = useStore((s) => s.state.settings);
  const releases = useStore((s) => s.state.releases);

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<InputRef>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const commands = useMemo<Command[]>(() => {
    const nav: Command[] = [
      { id: 'nav-dash', label: '打开概览', group: '导航', icon: <DashboardOutlined />, run: () => navigate('/') },
      { id: 'nav-rel', label: '打开发布单', group: '导航', icon: <UnorderedListOutlined />, run: () => navigate('/releases') },
      { id: 'nav-apps', label: '打开应用结构', group: '导航', icon: <AppstoreOutlined />, run: () => navigate('/apps') },
      { id: 'nav-set', label: '打开设置', group: '导航', icon: <SettingOutlined />, run: () => navigate('/settings') },
    ];
    const proto: Command[] = PROTO_REGISTRY.map((e) => ({
      id: `proto-${e.id}`,
      label: `原型 · ${e.title}`,
      group: '原型',
      icon: <ExperimentOutlined />,
      hint: e.domain,
      run: () => navigate(`/prototype/${e.id}`),
    }));
    const actions: Command[] = [
      { id: 'act-new', label: '新建发布单', group: '动作', icon: <PlusOutlined />, run: () => openModal({ type: 'release', id: null }) },
      {
        id: 'act-theme',
        label: `切换主题（当前${settings.theme === 'dark' ? '深色' : '浅色'}）`,
        group: '动作',
        icon: <SunOutlined />,
        run: () => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: settings.theme === 'dark' ? 'light' : 'dark' } }),
      },
      {
        id: 'act-accent',
        label: '循环强调色',
        group: '动作',
        icon: <ExperimentOutlined />,
        run: () => {
          const i = ACCENT_ORDER.indexOf(settings.accent);
          dispatch({ type: 'UPDATE_SETTINGS', patch: { accent: ACCENT_ORDER[(i + 1) % ACCENT_ORDER.length] } });
        },
      },
      {
        id: 'act-export',
        label: '导出当前数据备份',
        group: '动作',
        icon: <ExportOutlined />,
        run: () => {
          const blob = new Blob([JSON.stringify(useStore.getState().state, null, 2)], { type: 'application/json' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'acme-prototype-export.json';
          a.click();
          setTimeout(() => URL.revokeObjectURL(url), 800);
        },
      },
    ];
    const rels: Command[] = releases.slice(0, 10).map((r) => ({
      id: `rel-${r.id}`,
      label: r.title,
      group: '动作',
      icon: <UnorderedListOutlined />,
      hint: `${r.id} · v${r.version}`,
      run: () => {
        navigate('/releases');
        openDrawer(r.id);
      },
    }));
    return [...nav, ...actions, ...proto, ...rels];
  }, [navigate, dispatch, openModal, openDrawer, settings, releases]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? commands.filter((c) => `${c.label}${c.hint ?? ''}`.toLowerCase().includes(q)) : commands;
  }, [commands, query]);

  useEffect(() => {
    const onOpen = () => {
      setOpen(true);
      setQuery('');
      setActive(0);
      setTimeout(() => inputRef.current?.focus(), 30);
    };
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        e.stopPropagation();
        onOpen();
      }
    };
    window.addEventListener(OPEN_EVENT, onOpen);
    window.addEventListener('keydown', onKey, { capture: true });
    return () => {
      window.removeEventListener(OPEN_EVENT, onOpen);
      window.removeEventListener('keydown', onKey, { capture: true });
    };
  }, []);

  useEffect(() => setActive(0), [query]);

  const runAt = (i: number) => {
    const cmd = filtered[i];
    if (!cmd) return;
    setOpen(false);
    cmd.run();
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => Math.min(filtered.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      runAt(active);
    }
  };

  useEffect(() => {
    listRef.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  return (
    <>
      <Button
        shape="circle"
        style={{ position: 'fixed', right: 22, bottom: 24, zIndex: 1010, boxShadow: 'var(--shadow-pop)' }}
        onClick={() => window.dispatchEvent(new Event(OPEN_EVENT))}
        aria-label="打开命令面板（Ctrl+K）"
      >
        ⌘K
      </Button>
      {open && (
        <div
          role="dialog"
          aria-label="命令面板"
          onClick={() => setOpen(false)}
          style={{ position: 'fixed', inset: 0, zIndex: 1011, background: 'var(--scrim, rgba(0,0,0,.4))', display: 'flex', justifyContent: 'center', alignItems: 'flex-start', paddingTop: '10vh' }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            onKeyDown={onKeyDown}
            style={{
              width: 560,
              maxWidth: '92vw',
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              borderRadius: 14,
              boxShadow: 'var(--shadow-card)',
              overflow: 'hidden',
            }}
          >
            <div style={{ padding: 12, borderBottom: '1px solid var(--border)' }}>
              <Input
                ref={inputRef}
                variant="borderless"
                size="large"
                placeholder="搜索命令、页面、原型、发布单…（↑↓ 选择，回车执行）"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                aria-label="命令搜索"
              />
            </div>
            <div ref={listRef} style={{ maxHeight: 380, overflow: 'auto', padding: 6 }}>
              {filtered.length === 0 && (
                <div style={{ padding: 24, textAlign: 'center', color: 'var(--muted)', fontSize: 13 }}>没有匹配的命令</div>
              )}
              {filtered.map((c, i) => (
                <div
                  key={c.id}
                  data-index={i}
                  role="option"
                  aria-selected={i === active}
                  tabIndex={-1}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => runAt(i)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '9px 10px',
                    borderRadius: 10,
                    cursor: 'pointer',
                    background: i === active ? 'var(--surface-3)' : 'transparent',
                    color: i === active ? 'var(--accent)' : 'var(--text)',
                  }}
                >
                  <span style={{ fontSize: 14, width: 18 }}>{c.icon}</span>
                  <Text style={{ flex: 1, color: 'inherit' }}>{c.label}</Text>
                  {c.hint && <Text type="secondary" style={{ fontSize: 11 }}>{c.hint}</Text>}
                  <Tag style={{ fontSize: 10, marginInlineEnd: 0 }}>{c.group}</Tag>
                  {i === active && (
                    <span style={{ display: 'inline-flex', gap: 2, color: 'var(--muted)', fontSize: 10 }}>
                      <UpOutlined /><DownOutlined />
                    </span>
                  )}
                </div>
              ))}
            </div>
            <div style={{ padding: '8px 12px', borderTop: '1px solid var(--border)', fontSize: 11, color: 'var(--muted)' }}>
              ↑↓ 选择 · Enter 执行 · Esc 关闭 · 共 {filtered.length} 条命令
            </div>
          </div>
        </div>
      )}
    </>
  );
}
