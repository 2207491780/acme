import { useState } from 'react';
import { App, Avatar, Button, Card, Segmented, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { UserOutlined } from '@ant-design/icons';
import { MEMBERS, PERMISSION_MATRIX, ROLE_LABEL, ROLE_TONE, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

interface Row {
  action: string;
  roles: string[];
}

/** D1 成员与角色：成员卡片 + 角色×权限矩阵（原型仅本地生效）。 */
export default function Members() {
  const { message } = App.useApp();
  const apps = useStore((s) => s.state.apps);
  const [view, setView] = useState<'members' | 'matrix'>('members');

  const columns: ColumnsType<Row> = [
    { title: '操作', dataIndex: 'action', width: 200 },
    {
      title: '允许的角色',
      dataIndex: 'roles',
      render: (_, row) => (
        <Space size={4} wrap>
          {(['owner', 'dev', 'qa', 'ops', 'viewer'] as const).map((role) => {
            const allowed = row.roles.includes(role);
            return (
              <Tag key={role} color={allowed ? TONE_ANTD[ROLE_TONE[role]] : 'default'} style={{ opacity: allowed ? 1 : 0.45 }}>
                {ROLE_LABEL[role]}
                {allowed ? ' ✓' : ''}
              </Tag>
            );
          })}
        </Space>
      ),
    },
  ];

  return (
    <PageShell
      title="成员与角色"
      domain="协作域 D1"
      desc="团队成员、所属应用与权限矩阵；审批人池（B1）从这里取人。"
      extra={
        <Segmented
          value={view}
          onChange={(v) => setView(v as 'members' | 'matrix')}
          options={[
            { value: 'members', label: '成员' },
            { value: 'matrix', label: '权限矩阵' },
          ]}
          aria-label="切换成员/权限视图"
        />
      }
    >
      {view === 'members' ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 14 }}>
          {MEMBERS.map((m) => (
            <Card key={m.id} size="small">
              <Space style={{ marginBottom: 10 }}>
                <Avatar style={{ background: 'var(--accent)' }} icon={<UserOutlined />} aria-label={`${m.name} 头像`} />
                <div>
                  <Space size={6}>
                    <Text strong>{m.name}</Text>
                    <Tag color={TONE_ANTD[ROLE_TONE[m.role]]}>{ROLE_LABEL[m.role]}</Tag>
                  </Space>
                  <div style={{ fontSize: 11, color: 'var(--muted)' }}>{m.email}</div>
                </div>
              </Space>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {m.apps.length ? (
                  m.apps.map((id) => {
                    const app = apps.find((a) => a.id === id);
                    return <Tag key={id}>{app ? app.name : id}</Tag>;
                  })
                ) : (
                  <Text type="secondary" style={{ fontSize: 12 }}>未分配应用</Text>
                )}
              </div>
              <Button
                size="small"
                type="text"
                style={{ marginTop: 8, paddingInline: 0 }}
                onClick={() => message.info('原型演示：编辑成员仅本地生效')}
                aria-label={`编辑成员 ${m.name}`}
              >
                编辑分配
              </Button>
            </Card>
          ))}
        </div>
      ) : (
        <Section title="角色 × 权限矩阵">
          <Table<Row>
            size="small"
            rowKey="action"
            columns={columns}
            dataSource={PERMISSION_MATRIX}
            pagination={false}
            aria-label="权限矩阵"
          />
        </Section>
      )}
      <HintText style={{ marginTop: 12 }}>
        演示口径：权限按角色白名单；成熟化时接真实身份源（SSO/组织架构），并支持应用级细分授权。
      </HintText>
    </PageShell>
  );
}
