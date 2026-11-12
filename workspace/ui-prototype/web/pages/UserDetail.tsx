import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Avatar, Button, Card, Select, Space, Tabs, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, AuditOutlined, CheckOutlined, ClockCircleOutlined, UserOutlined } from '@ant-design/icons';
import { fmtDate, fmtFull, memberDirectory, memberProfile, MEMBERS, ROLE_LABEL, ROLE_TONE, statusLabel, statusTone, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section, ToneDot } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;
const DECISION_COLOR = { approved: 'success', rejected: 'error', pending: 'default' } as const;

/** U2 用户详情：个人档案 + 负责发布单 + 审批记录 + 操作留痕（正式 store 只读推导）。 */
export default function UserDetail() {
  const state = useStore((s) => s.state);
  const [params, setParams] = useSearchParams();
  const rows = useMemo(() => memberDirectory(state, Date.now()), [state]);
  const name = params.get('user') ?? MEMBERS[0].name;
  const profile = useMemo(() => memberProfile(state, name, Date.now()), [state, name]);

  if (!profile) {
    return (
      <PageShell title="用户详情" domain="用户域 U2" desc="成员不在册。">
        <EmptyState title="未找到该成员" desc={`「${name}」不在成员目录中。`} />
      </PageShell>
    );
  }

  const { member } = profile;
  const workload = rows.find((r) => r.member.name === name)!;
  const activeReleases = profile.ownedReleases.filter((r) => ['draft', 'building', 'testing', 'approved'].includes(r.status));

  return (
    <PageShell
      title={`${member.name}（用户详情）`}
      domain="用户域 U2"
      desc="个人工作档案：负责发布单、审批签署、待办与操作留痕。"
      extra={
        <Select
          style={{ width: 160 }}
          value={name}
          onChange={(v) => setParams({ user: v })}
          aria-label="切换成员"
          options={MEMBERS.map((m) => ({ value: m.name, label: m.name }))}
        />
      }
    >
      <div style={{ marginBottom: 12 }}>
        <Link to="/prototype/users">
          <Button size="small" icon={<ArrowLeftOutlined />}>返回用户列表</Button>
        </Link>
      </div>

      <Card size="small" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          <Avatar size={52} style={{ background: 'var(--accent)', fontSize: 20 }} icon={<UserOutlined />} aria-label={`${member.name} 头像`} />
          <div style={{ flex: 1, minWidth: 200 }}>
            <Space size={8} wrap>
              <Text strong style={{ fontSize: 17 }}>{member.name}</Text>
              <Tag color={TONE_ANTD[ROLE_TONE[member.role]]}>{ROLE_LABEL[member.role]}</Tag>
              {member.apps.map((id) => (
                <Tag key={id}>{state.apps.find((a) => a.id === id)?.name ?? id}</Tag>
              ))}
            </Space>
            <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 4 }}>
              {member.email} · 最近活动 {profile.lastActiveAt ? fmtDate(profile.lastActiveAt) : '—'}
            </div>
          </div>
          {workload.pendingApprovals > 0 && (
            <Tag color="warning" style={{ fontSize: 12 }}>
              <ClockCircleOutlined /> {workload.pendingApprovals} 个审批待办
            </Tag>
          )}
        </div>
      </Card>

      <KpiRow>
        <Kpi label="负责发布单" value={profile.ownedReleases.length} tone="info" />
        <Kpi label="进行中" value={activeReleases.length} tone="violet" />
        <Kpi label="已发布" value={profile.ownedReleases.filter((r) => r.status === 'published').length} tone="success" />
        <Kpi label="审批签署" value={profile.approvalRecords.filter((x) => x.decision !== 'pending').length} tone="warning" />
      </KpiRow>

      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Section title={`负责的发布单（${profile.ownedReleases.length}）`}>
            {profile.ownedReleases.length === 0 ? (
              <HintText>暂无负责的发布单。</HintText>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                {profile.ownedReleases.map((r) => (
                  <Link key={r.id} to={`/prototype/release-detail?release=${r.id}`} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 0', borderBottom: '1px solid var(--border)' }}>
                    <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
                    <Text style={{ fontSize: 13, flex: 1 }} ellipsis>{r.title}</Text>
                    <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{r.id} · v{r.version}</Text>
                  </Link>
                ))}
              </div>
            )}
          </Section>

          <Section title={`操作留痕（${profile.audit.length}）`}>
            {profile.audit.length === 0 ? (
              <HintText>暂无留痕记录。</HintText>
            ) : (
              profile.audit.map((e) => (
                <div key={e.id} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border)', fontSize: 12 }}>
                  <ToneDot tone={e.tone} />
                  <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11, width: 110 }}>{fmtDate(e.at)}</Text>
                  <Tag>{e.action}</Tag>
                  <Text style={{ fontFamily: 'var(--mono)' }}>{e.target}</Text>
                  <Text type="secondary" style={{ flex: 1 }}>{e.detail}</Text>
                </div>
              ))
            )}
          </Section>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card size="small">
            <Tabs
              defaultActiveKey="todo"
              items={[
                {
                  key: 'todo',
                  label: `审批待办（${profile.pendingApprovals.length}）`,
                  children: (
                    <>
                      {profile.pendingApprovals.length === 0 ? (
                        <HintText>没有等待该成员处理的审批。</HintText>
                      ) : (
                        profile.pendingApprovals.map((p) => (
                          <div key={`${p.releaseId}-${p.stageKey}`} style={{ padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                            <Space size={6} wrap>
                              <Tag color="warning">{p.stageLabel}</Tag>
                              <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{p.releaseId}</Text>
                              <Text type="secondary" style={{ fontSize: 12 }}>{p.releaseTitle}</Text>
                            </Space>
                            <div style={{ marginTop: 6 }}>
                              <Link to={`/prototype/approval?release=${p.releaseId}`}>
                                <Button size="small" type="primary" icon={<CheckOutlined />}>去处理</Button>
                              </Link>
                            </div>
                          </div>
                        ))
                      )}
                    </>
                  ),
                },
                {
                  key: 'signed',
                  label: `签署记录（${profile.approvalRecords.filter((x) => x.decision !== 'pending').length}）`,
                  children: (
                    <>
                      {profile.approvalRecords.filter((x) => x.decision !== 'pending').length === 0 ? (
                        <HintText>暂无签署记录。</HintText>
                      ) : (
                        <Space direction="vertical" size={8} style={{ width: '100%' }}>
                          {profile.approvalRecords.filter((x) => x.decision !== 'pending').map((p) => (
                            <div key={`${p.releaseId}-${p.stageKey}`} style={{ padding: 10, borderRadius: 10, border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                              <Space size={6} wrap style={{ marginBottom: 4 }}>
                                <Tag color={DECISION_COLOR[p.decision]}>{p.decision === 'approved' ? '已通过' : '已驳回'} · {p.stageLabel}</Tag>
                                <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{p.releaseId}</Text>
                                {p.at && <Text type="secondary" style={{ fontSize: 11 }}>{fmtFull(p.at)}</Text>}
                              </Space>
                              <div style={{ fontSize: 12, color: 'var(--text-2)' }}>{p.comment}</div>
                            </div>
                          ))}
                        </Space>
                      )}
                      <HintText style={{ marginTop: 8 }}>
                        <AuditOutlined /> 签署意见由审批链（A1 口径）确定性生成。
                      </HintText>
                    </>
                  ),
                },
              ]}
            />
          </Card>
        </div>
      </div>
    </PageShell>
  );
}
