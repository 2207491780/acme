import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { App, Avatar, Button, Select, Space, Tag, Typography } from 'antd';
import { AuditOutlined, CheckOutlined, CloseOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
import { fmtDate, memberProfile, MEMBERS, ROLE_LABEL, ROLE_TONE, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** U3 我的工作台：以「当前身份」聚合待我审批 / 我负责的进行中单 / 我的动态（原型仅本地生效）。 */
export default function MyDesk() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const [identity, setIdentity] = useState(MEMBERS.find((m) => m.role === 'owner')?.name ?? MEMBERS[0].name);
  const [decided, setDecided] = useState<Record<string, 'approved' | 'rejected'>>({});
  const profile = useMemo(() => memberProfile(state, identity, Date.now()), [state, identity]);

  if (!profile) return <EmptyState title="成员不在册" />;

  const { member } = profile;
  const activeOwned = profile.ownedReleases.filter((r) => ['building', 'testing', 'approved'].includes(r.status));
  const todo = profile.pendingApprovals.filter((p) => !decided[`${p.releaseId}-${p.stageKey}`]);

  const decide = (key: string, decision: 'approved' | 'rejected', releaseId: string) => {
    setDecided((cur) => ({ ...cur, [key]: decision }));
    message[decision === 'approved' ? 'success' : 'warning'](
      `原型演示：${releaseId} ${decision === 'approved' ? '已通过' : '已驳回'}（仅本地生效）`,
    );
  };

  return (
    <PageShell
      title="我的工作台"
      domain="用户域 U3"
      desc="个人视角的聚合入口：待我处理 → 我负责的 → 我的留痕；身份可切换用于走查不同角色。"
      extra={
        <Space>
          <Avatar size={30} style={{ background: 'var(--accent)' }} icon={<UserOutlined />} aria-label="当前身份头像" />
          <Select
            style={{ width: 130 }}
            value={identity}
            onChange={setIdentity}
            aria-label="切换当前身份"
            options={MEMBERS.map((m) => ({ value: m.name, label: m.name }))}
          />
        </Space>
      }
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <Tag color={TONE_ANTD[ROLE_TONE[member.role]]}>{ROLE_LABEL[member.role]}</Tag>
        <Text type="secondary" style={{ fontSize: 12 }}>
          当前身份：{member.name} · 切换右上角下拉可走查其他成员视角
        </Text>
      </div>

      <KpiRow>
        <Kpi label="待我审批" value={todo.length} tone="warning" hint={todo.length ? '优先处理' : undefined} />
        <Kpi label="我负责·进行中" value={activeOwned.length} tone="violet" />
        <Kpi label="我负责·已发布" value={profile.ownedReleases.filter((r) => r.status === 'published').length} tone="success" />
        <Kpi label="累计签署" value={profile.approvalRecords.filter((x) => x.decision !== 'pending').length} tone="info" />
      </KpiRow>

      <Section
        title={
          <Space size={6}>
            <ThunderboltOutlined style={{ color: 'var(--accent)' }} /> 待我审批（{todo.length}）
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        {todo.length === 0 ? (
          <HintText>全部处理完毕，或当前身份无审批权责。</HintText>
        ) : (
          todo.map((p) => {
            const key = `${p.releaseId}-${p.stageKey}`;
            return (
              <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0', borderBottom: '1px solid var(--border)', flexWrap: 'wrap' }}>
                <Tag color="warning">{p.stageLabel}</Tag>
                <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{p.releaseId}</Text>
                <Text style={{ fontSize: 13, flex: 1, minWidth: 160 }} ellipsis>{p.releaseTitle}</Text>
                <Space size={6}>
                  <Button size="small" icon={<CheckOutlined />} onClick={() => decide(key, 'approved', p.releaseId)}>通过</Button>
                  <Button size="small" danger icon={<CloseOutlined />} onClick={() => decide(key, 'rejected', p.releaseId)}>驳回</Button>
                  <Button size="small" type="text" icon={<AuditOutlined />} onClick={() => navigate(`/prototype/approval?release=${p.releaseId}`)}>详情</Button>
                </Space>
              </div>
            );
          })
        )}
      </Section>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <Section title={`我负责的进行中（${activeOwned.length}）`}>
          {activeOwned.length === 0 ? (
            <HintText>暂无进行中的发布单。</HintText>
          ) : (
            activeOwned.map((r) => (
              <Link key={r.id} to={`/prototype/release-detail?release=${r.id}`} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0' }}>
                <Tag>{r.id}</Tag>
                <Text style={{ fontSize: 12.5, flex: 1 }} ellipsis>{r.title}</Text>
                <Tag color="processing">{r.status === 'building' ? `构建 ${r.progress}%` : r.status}</Tag>
              </Link>
            ))
          )}
        </Section>

        <Section title={`我的最近动态（${Math.min(profile.audit.length, 6)}）`}>
          {profile.audit.slice(0, 6).map((e) => (
            <div key={e.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '5px 0', fontSize: 12 }}>
              <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{fmtDate(e.at)}</Text>
              <Tag>{e.action}</Tag>
              <Text>{e.target}</Text>
            </div>
          ))}
          {profile.audit.length === 0 && <HintText>暂无动态。</HintText>}
        </Section>
      </div>

      <HintText style={{ marginTop: 12 }}>
        成熟化时「当前身份」来自真实登录态；待办通过/驳回将调用审批 API 并写审计（联动 C3）。
      </HintText>
    </PageShell>
  );
}
