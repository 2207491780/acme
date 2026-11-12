import { useState } from 'react';
import { App, Avatar, Button, Input, Select, Space, Typography } from 'antd';
import { SendOutlined, UserOutlined } from '@ant-design/icons';
import { relTime, threads as loadThreads, type ProtoComment } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

function CommentNode({ node, depth = 0 }: { node: ProtoComment; depth?: number }) {
  return (
    <div style={{ marginLeft: depth * 24, marginTop: 10 }}>
      <Space size={8}>
        <Avatar size={22} style={{ background: 'var(--accent)' }} icon={<UserOutlined />} aria-label={`${node.author} 头像`} />
        <Text strong style={{ fontSize: 13 }}>{node.author}</Text>
        <Text type="secondary" style={{ fontSize: 11 }}>{relTime(node.at)}</Text>
      </Space>
      <div style={{ fontSize: 13, marginTop: 4 }}>{node.text}</div>
      {node.replies.map((r) => (
        <CommentNode key={r.id} node={r} depth={depth + 1} />
      ))}
    </div>
  );
}

/** D3 发布单评论区：thread + 回复 + @提及提示（原型仅本地生效）。 */
export default function Comments() {
  const { message } = App.useApp();
  const releases = useStore((s) => s.state.releases);
  const [releaseId, setReleaseId] = useState(releases[0]?.id ?? '');
  const [draft, setDraft] = useState('');
  const [extra, setExtra] = useState<Record<string, ProtoComment[]>>({});

  const base = loadThreads({ releases }, Date.now());
  const thread = base.find((t) => t.releaseId === releaseId);
  const items = [...(thread?.items ?? []), ...(extra[releaseId] ?? [])];

  const submit = () => {
    if (!draft.trim()) return;
    const mention = draft.includes('@');
    setExtra((cur) => ({
      ...cur,
      [releaseId]: [
        ...(cur[releaseId] ?? []),
        { id: `local-${Date.now()}`, author: '我', at: new Date().toISOString(), text: draft.trim(), replies: [] },
      ],
    }));
    setDraft('');
    message.success(mention ? '原型演示：评论已提交，@ 提及将推送通知（仅本地生效）' : '原型演示：评论已提交（仅本地生效）');
  };

  return (
    <PageShell
      title="发布单评论区"
      domain="协作域 D3"
      desc="发布单内的讨论 thread：回复、@ 提及触发通知（联动 D2）；内容为演示数据 + 本地新增。"
      extra={
        <Select
          style={{ width: 260 }}
          value={releaseId || undefined}
          onChange={setReleaseId}
          placeholder="选择发布单"
          aria-label="选择发布单"
          options={releases.map((r) => ({ value: r.id, label: `${r.id} · ${r.title}` }))}
        />
      }
    >
      {!thread ? (
        <EmptyState title="请选择发布单" />
      ) : (
        <Section title={`讨论（${items.length} 条主楼）`}>
          {items.map((c) => (
            <CommentNode key={c.id} node={c} />
          ))}
          <div style={{ marginTop: 16 }}>
            <Input.TextArea
              rows={3}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder="写下你的讨论…输入 @ 可提及成员（如 @林晚）"
              maxLength={300}
              showCount
              aria-label="评论输入"
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 10 }}>
              <HintText>支持 @ 提及；提及成员会收到通知（联动 D2）。</HintText>
              <Button type="primary" icon={<SendOutlined />} onClick={submit}>
                发送
              </Button>
            </div>
          </div>
        </Section>
      )}
    </PageShell>
  );
}
