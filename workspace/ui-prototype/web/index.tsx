import { useState } from 'react';
import { Alert, Card, Input, Tag, Typography } from 'antd';
import { ExperimentOutlined, SearchOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { PageHead } from '../components/PageHead';
import { PROTO_DOMAINS, PROTO_REGISTRY } from './registry';
import CommandPalette from './components/CommandPalette';

const { Text } = Typography;

/** 原型索引页（/prototype）：按领域列出全部原型入口；不进正式导航。 */
export default function ProtoIndex() {
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  const visible = PROTO_REGISTRY.filter((e) => !q || `${e.title}${e.desc}${e.domain}`.toLowerCase().includes(q));

  return (
    <div>
      <CommandPalette />
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 14 }}>
        <Tag color="warning">原型预览</Tag>
        <span style={{ color: 'var(--muted)', fontSize: 12 }}>
          代码原型区 · 演示数据 · 不落库 · 生命周期见 web/src/prototypes/README.md
        </span>
      </div>
      <PageHead
        eyebrow="Prototypes"
        title="原型索引"
        sub={`共 ${PROTO_REGISTRY.length} 个原型（Web + APP 两端同步实施；PC 与 Web 同 UI 自动复用）。Ctrl/⌘+K 可全局搜索并直达任意原型。`}
        extra={
          <Input
            style={{ width: 240 }}
            prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
            placeholder="搜索原型…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            allowClear
            aria-label="搜索原型"
          />
        }
      />
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="原型页不进正式导航与生产承诺：交互仅本地生效，评审通过后迁入正式页面。"
      />
      {PROTO_DOMAINS.map((domain) => {
        const items = visible.filter((e) => e.domain === domain);
        if (!items.length) return null;
        return (
          <div key={domain} style={{ marginBottom: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '4px 0 10px' }}>
              <ExperimentOutlined style={{ color: 'var(--accent)' }} />
              <Text strong>{domain}</Text>
              <Tag>{items.length}</Tag>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12 }}>
              {items.map((e) => (
                <Link key={e.id} to={`/prototype/${e.id}`} aria-label={`打开原型 ${e.title}`}>
                  <Card
                    size="small"
                    hoverable
                    style={{ height: '100%' }}
                    styles={{ body: { padding: 14 } }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                      <Text strong style={{ fontSize: 14 }}>{e.title}</Text>
                      <Tag color="warning" style={{ marginInlineEnd: 0 }}>原型</Tag>
                    </div>
                    <div style={{ fontSize: 12.5, color: 'var(--text-2)', marginTop: 6 }}>{e.desc}</div>
                    <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 8, fontFamily: 'var(--mono)' }}>/prototype/{e.id}</div>
                  </Card>
                </Link>
              ))}
            </div>
          </div>
        );
      })}
      {visible.length === 0 && (
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--muted)' }}>没有匹配的原型，换个关键词试试。</div>
      )}
    </div>
  );
}
