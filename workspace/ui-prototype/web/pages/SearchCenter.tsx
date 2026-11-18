import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Empty, Input, Segmented, Tag, Typography } from 'antd';
import { AppstoreOutlined, SearchOutlined, UnorderedListOutlined, UserOutlined } from '@ant-design/icons';
import { globalSearch, MEMBERS } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

const KIND_META = {
  release: { label: '发布单', icon: <UnorderedListOutlined />, to: (id: string) => `/prototype/release-detail?release=${id}` },
  app: { label: '应用', icon: <AppstoreOutlined />, to: () => '/prototype/app-detail' },
  member: { label: '成员', icon: <UserOutlined />, to: (id: string) => `/prototype/user-detail?user=${encodeURIComponent(id)}` },
} as const;

/** X1 全局搜索中心：聚合发布单 / 应用 / 成员的即时搜索结果（F2 命令面板的数据面）。 */
export default function SearchCenter() {
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<'all' | 'release' | 'app' | 'member'>('all');

  const members = useMemo(() => MEMBERS.map((m) => ({ name: m.name, email: m.email })), []);
  const hits = useMemo(() => {
    const all = globalSearch(state, members, query, 40);
    return kind === 'all' ? all : all.filter((h) => h.kind === kind);
  }, [state, members, query, kind]);

  const grouped = useMemo(() => {
    const map = new Map<string, typeof hits>();
    for (const h of hits) map.set(h.kind, [...(map.get(h.kind) ?? []), h]);
    return [...map.entries()];
  }, [hits]);

  return (
    <PageShell
      title="全局搜索中心"
      domain="横向专项 X1"
      desc="聚合发布单 / 应用 / 成员的即时搜索；与 F2 命令面板（Ctrl+K）共享同一数据口径，此页展示完整结果与分组。"
      extra={
        <Segmented
          value={kind}
          onChange={(v) => setKind(v as typeof kind)}
          options={[
            { value: 'all', label: '全部' },
            { value: 'release', label: '发布单' },
            { value: 'app', label: '应用' },
            { value: 'member', label: '成员' },
          ]}
          aria-label="结果类型过滤"
        />
      }
    >
      <Section title="搜索">
        <Input
          size="large"
          prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
          placeholder="搜发布单 ID / 标题 / 版本 / 应用 / 成员…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          allowClear
          autoFocus
          aria-label="全局搜索"
        />
        <HintText style={{ marginTop: 8 }}>
          {query.trim() ? `「${query.trim()}」命中 ${hits.length} 条` : '输入关键词开始搜索；Ctrl+K 可在任意页面唤起命令面板。'}
        </HintText>
      </Section>

      {query.trim() && hits.length === 0 ? (
        <Empty description="没有匹配的结果，换个关键词试试。" style={{ padding: 24 }} />
      ) : (
        grouped.map(([kindKey, list]) => (
          <Section key={kindKey} title={`${KIND_META[kindKey as keyof typeof KIND_META].label}（${list.length}）`} style={{ marginTop: 14 }}>
            {list.map((h) => (
              <div
                key={`${h.kind}-${h.id}`}
                onClick={() => navigate(KIND_META[h.kind as keyof typeof KIND_META].to(h.id))}
                style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '8px 10px', borderRadius: 8, cursor: 'pointer', borderBottom: '1px solid var(--border)' }}
              >
                {KIND_META[h.kind as keyof typeof KIND_META].icon}
                <Text strong style={{ fontSize: 13, flex: 1 }} ellipsis>{h.title}</Text>
                <Text type="secondary" style={{ fontSize: 11 }}>{h.sub}</Text>
                <Tag style={{ fontSize: 10 }}>{KIND_META[h.kind as keyof typeof KIND_META].label}</Tag>
              </div>
            ))}
          </Section>
        ))
      )}
    </PageShell>
  );
}
