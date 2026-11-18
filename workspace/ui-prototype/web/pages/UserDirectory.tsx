import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Avatar, Input, Segmented, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined, UserOutlined } from '@ant-design/icons';
import { fmtDate, memberDirectory, ROLE_LABEL, ROLE_TONE, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;
const ROLES = ['all', 'owner', 'dev', 'qa', 'ops', 'viewer'] as const;

/** U1 用户列表：成员目录 + 工作量/审批负荷一览（正式 store 只读推导），点击行进入 U2 详情。 */
export default function UserDirectory() {
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const rows = useMemo(() => memberDirectory(state, Date.now()), [state]);
  const [query, setQuery] = useState('');
  const [role, setRole] = useState<(typeof ROLES)[number]>('all');

  const filtered = rows.filter(
    (row) =>
      (role === 'all' || row.member.role === role) &&
      (!query.trim() || `${row.member.name}${row.member.email}`.toLowerCase().includes(query.trim().toLowerCase())),
  );

  const columns: ColumnsType<(typeof rows)[number]> = [
    {
      title: '成员',
      key: 'name',
      render: (_, row) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Avatar size={30} style={{ background: 'var(--accent)' }} icon={<UserOutlined />} aria-label={`${row.member.name} 头像`} />
          <div>
            <Text strong>{row.member.name}</Text>
            <div style={{ fontSize: 11, color: 'var(--muted)' }}>{row.member.email}</div>
          </div>
        </div>
      ),
    },
    {
      title: '角色',
      key: 'role',
      width: 90,
      render: (_, row) => <Tag color={TONE_ANTD[ROLE_TONE[row.member.role]]}>{ROLE_LABEL[row.member.role]}</Tag>,
    },
    {
      title: '负责应用',
      key: 'apps',
      render: (_, row) =>
        row.member.apps.length ? <span style={{ fontSize: 12 }}>{row.member.apps.join('、')}</span> : <Text type="secondary" style={{ fontSize: 12 }}>—</Text>,
    },
    {
      title: '负责发布单',
      key: 'owned',
      width: 120,
      sorter: (a, b) => b.ownedTotal - a.ownedTotal,
      render: (_, row) => (
        <span style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>
          {row.ownedTotal}
          <Text type="secondary">（进行 {row.ownedActive}）</Text>
        </span>
      ),
    },
    {
      title: '待其审批',
      key: 'pending',
      width: 100,
      sorter: (a, b) => b.pendingApprovals - a.pendingApprovals,
      render: (_, row) =>
        row.pendingApprovals > 0 ? <Tag color="warning">{row.pendingApprovals} 待办</Tag> : <Text type="secondary" style={{ fontSize: 12 }}>—</Text>,
    },
    {
      title: '最近活动',
      key: 'last',
      width: 130,
      render: (_, row) => <Text type="secondary" style={{ fontSize: 12 }}>{row.lastActiveAt ? fmtDate(row.lastActiveAt) : '—'}</Text>,
    },
    { title: '', key: 'go', width: 40, render: () => <Text style={{ color: 'var(--muted)' }}>›</Text> },
  ];

  const totalPending = rows.reduce((n, r) => n + r.pendingApprovals, 0);
  const totalOwned = rows.reduce((n, r) => n + r.ownedTotal, 0);

  return (
    <PageShell
      title="用户列表"
      domain="用户域 U1"
      desc="成员目录与工作负荷：负责人字段、审批待办、最近活动一表看清；点击行进入个人详情（U2）。"
    >
      <KpiRow>
        <Kpi label="成员数" value={rows.length} tone="info" />
        <Kpi label="负责发布单" value={totalOwned} tone="violet" />
        <Kpi label="审批待办合计" value={totalPending} tone="warning" hint={totalPending ? '点行进入详情处理' : undefined} />
        <Kpi label="只读成员" value={rows.filter((r) => r.member.role === 'viewer').length} tone="muted" />
      </KpiRow>

      <Section
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Input
              size="small"
              style={{ width: 200 }}
              prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
              placeholder="搜索姓名 / 邮箱"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              allowClear
              aria-label="搜索成员"
            />
            <Segmented
              size="small"
              value={role}
              onChange={(v) => setRole(v as (typeof ROLES)[number])}
              options={ROLES.map((r) => ({ value: r, label: r === 'all' ? '全部' : ROLE_LABEL[r] }))}
              aria-label="按角色过滤"
            />
            <Tag>{filtered.length} 人</Tag>
          </div>
        }
      >
        <Table
          size="small"
          rowKey={(row) => row.member.id}
          columns={columns}
          dataSource={filtered}
          pagination={false}
          aria-label="成员目录"
          onRow={(row) => ({
            onClick: () => navigate(`/prototype/user-detail?user=${encodeURIComponent(row.member.name)}`),
            style: { cursor: 'pointer' },
          })}
          locale={{ emptyText: '没有匹配的成员。' }}
        />
      </Section>
      <HintText style={{ marginTop: 12 }}>
        与 D1（角色权限管理）的分工：D1 是管理员视角的权限配置，U1 是带工作负荷的目录视图；两者共享 MEMBERS 数据源。
      </HintText>
    </PageShell>
  );
}
