import { useMemo, useState } from 'react';
import { App, Button, Input, Select, Space, Tag, Typography } from 'antd';
import { DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import { fmtFull, auditTrail, type AuditEntry } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section, ToneDot } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** C3 活动审计：全量操作日志 + 人员/动作筛选 + 导出（原型仅本地生效）。 */
export default function AuditTrailPage() {
  const { message } = App.useApp();
  const releases = useStore((s) => s.state.releases);
  const [entries] = useState<AuditEntry[]>(() => auditTrail({ releases }, Date.now()));
  const [actor, setActor] = useState<string>('all');
  const [action, setAction] = useState<string>('all');
  const [keyword, setKeyword] = useState('');

  const actors = useMemo(() => [...new Set(entries.map((e) => e.actor))], [entries]);
  const actions = useMemo(() => [...new Set(entries.map((e) => e.action))], [entries]);
  const filtered = entries.filter(
    (e) =>
      (actor === 'all' || e.actor === actor) &&
      (action === 'all' || e.action === action) &&
      (!keyword.trim() || `${e.target}${e.detail}`.toLowerCase().includes(keyword.trim().toLowerCase())),
  );

  return (
    <PageShell
      title="活动审计"
      domain="观测域 C3"
      desc="谁在什么时候对哪个发布单做了什么：按人 / 动作 / 关键词过滤，支持导出留痕。"
      extra={
        <Button
          icon={<DownloadOutlined />}
          onClick={() => message.info('原型演示：导出仅本地生效')}
        >
          导出 CSV
        </Button>
      }
    >
      <Section
        title={
          <Space wrap size={8}>
            <Select
              size="small"
              style={{ width: 120 }}
              value={actor}
              onChange={setActor}
              aria-label="按人员筛选"
              options={[{ value: 'all', label: '全部人员' }, ...actors.map((a) => ({ value: a, label: a }))]}
            />
            <Select
              size="small"
              style={{ width: 130 }}
              value={action}
              onChange={setAction}
              aria-label="按动作筛选"
              options={[{ value: 'all', label: '全部动作' }, ...actions.map((a) => ({ value: a, label: a }))]}
            />
            <Input
              size="small"
              style={{ width: 200 }}
              prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
              placeholder="搜索目标 / 详情"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              allowClear
              aria-label="搜索审计条目"
            />
            <Tag>{filtered.length} 条</Tag>
          </Space>
        }
      >
        {filtered.length === 0 ? (
          <EmptyState title="没有匹配的审计条目" desc="调整筛选条件后重试。" />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            {filtered.map((e) => (
              <div
                key={e.id}
                style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '8px 0', borderBottom: '1px solid var(--border)' }}
              >
                <ToneDot tone={e.tone} />
                <Text type="secondary" style={{ flex: 'none', fontFamily: 'var(--mono)', fontSize: 11, width: 118 }}>
                  {fmtFull(e.at)}
                </Text>
                <Text strong style={{ width: 56, flex: 'none' }}>{e.actor}</Text>
                <Tag style={{ flex: 'none' }}>{e.action}</Tag>
                <Text style={{ fontFamily: 'var(--mono)', fontSize: 12, flex: 'none' }}>{e.target}</Text>
                <Text type="secondary" style={{ fontSize: 12, flex: 1 }}>{e.detail}</Text>
              </div>
            ))}
          </div>
        )}
      </Section>
      <HintText style={{ marginTop: 12 }}>
        审计流为演示数据（确定性生成）；成熟化时由真实动作事件写入，并保留至少 90 天。
      </HintText>
    </PageShell>
  );
}
