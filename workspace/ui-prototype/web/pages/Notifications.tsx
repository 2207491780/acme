import { useMemo, useState } from 'react';
import { App, Badge, Button, Segmented, Space, Tag, Typography } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { CATEGORY_LABEL, fmtDate, notices, type Notice } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { Section, ToneDot } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** D2 通知中心：分类过滤、已读/未读、全部已读（原型仅本地生效）。 */
export default function Notifications() {
  const { message } = App.useApp();
  const [items, setItems] = useState<Notice[]>(() => notices(Date.now()));
  const [filter, setFilter] = useState<'all' | Notice['category'] | 'unread'>('all');

  const filtered = useMemo(
    () =>
      items.filter((n) =>
        filter === 'all' ? true : filter === 'unread' ? !n.read : n.category === filter,
      ),
    [items, filter],
  );
  const unread = items.filter((n) => !n.read).length;

  const readOne = (id: string) => setItems((cur) => cur.map((n) => (n.id === id ? { ...n, read: true } : n)));

  return (
    <PageShell
      title="通知中心"
      domain="协作域 D2"
      desc="审批 / 构建 / 提及 / 系统通知的聚合收件箱；成熟化时与 A1 审批、D3 评论联动推送。"
      extra={
        <Button
          icon={<CheckOutlined />}
          onClick={() => {
            setItems((cur) => cur.map((n) => ({ ...n, read: true })));
            message.info('原型演示：全部已读仅本地生效');
          }}
        >
          全部已读
        </Button>
      }
    >
      <Section
        title={
          <Space wrap size={8}>
            <Segmented
              value={filter}
              onChange={(v) => setFilter(v as typeof filter)}
              aria-label="通知分类"
              options={[
                { value: 'all', label: '全部' },
                { value: 'unread', label: <Badge count={unread} size="small" offset={[6, 0]}>未读</Badge> },
                ...(['approval', 'build', 'mention', 'system'] as const).map((c) => ({ value: c, label: CATEGORY_LABEL[c] })),
              ]}
            />
            <Tag>{filtered.length} 条</Tag>
          </Space>
        }
      >
        {filtered.length === 0 ? (
          <EmptyState title="没有通知" desc="有新的审批或构建事件时会出现在这里。" />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            {filtered.map((n) => (
              <div
                key={n.id}
                style={{
                  display: 'flex',
                  gap: 12,
                  alignItems: 'flex-start',
                  padding: '12px 0',
                  borderBottom: '1px solid var(--border)',
                  opacity: n.read ? 0.65 : 1,
                }}
              >
                <ToneDot tone={n.tone} />
                <div style={{ flex: 1 }}>
                  <Space size={6} wrap>
                    {!n.read && <Badge status="processing" />}
                    <Text strong={!n.read}>{n.title}</Text>
                    <Tag style={{ fontSize: 11 }}>{CATEGORY_LABEL[n.category]}</Tag>
                  </Space>
                  <div style={{ fontSize: 12.5, color: 'var(--text-2)', marginTop: 2 }}>{n.body}</div>
                  <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 4 }}>{fmtDate(n.at)}</div>
                </div>
                {!n.read && (
                  <Button size="small" type="text" onClick={() => readOne(n.id)} aria-label={`标记已读：${n.title}`}>
                    已读
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </Section>
    </PageShell>
  );
}
