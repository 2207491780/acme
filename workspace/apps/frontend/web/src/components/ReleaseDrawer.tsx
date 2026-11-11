import type { ReactNode } from 'react';
import { App, Button, Descriptions, Drawer, Empty, Progress, Space, Timeline, Typography } from 'antd';
import {
  AlertOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  FileZipOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { NEXT_STATUS, STATUS_ACTION, fmtFull, statusColor, type Status } from '@acme/core';
import { useStore } from '../store/useStore';
import { EnvTag, PlatformTag, StatusTag } from './atoms';
import { useDeleteReleases } from '../hooks/useReleaseActions';
import { copyText } from '../utils/dom';

const { Title, Text, Paragraph } = Typography;

function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <Text type="secondary" strong style={{ display: 'block', margin: '20px 0 10px', fontSize: 11, letterSpacing: 1.6, textTransform: 'uppercase' }}>
      {children}
    </Text>
  );
}

export function ReleaseDrawer() {
  const { message } = App.useApp();
  const drawerId = useStore((s) => s.drawerId);
  const release = useStore((s) => s.state.releases.find((r) => r.id === s.drawerId));
  const apps = useStore((s) => s.state.apps);
  const closeDrawer = useStore((s) => s.closeDrawer);
  const dispatch = useStore((s) => s.dispatch);
  const openModal = useStore((s) => s.openModal);
  const advance = useStore((s) => s.advance);
  const remove = useDeleteReleases();

  const app = release && apps.find((a) => a.id === release.appId);
  const next = release && NEXT_STATUS[release.status];
  const canFail = release && ['building', 'testing'].includes(release.status);
  const canCancel = release && ['draft', 'building', 'testing', 'approved'].includes(release.status);

  const setStatus = (status: Status) => {
    if (release) dispatch({ type: 'SET_STATUS', id: release.id, status });
  };

  const copy = async (text: string) => {
    const ok = await copyText(text);
    message[ok ? 'success' : 'error'](ok ? `已复制：${text}` : '复制失败，请手动复制');
  };

  return (
    <Drawer
      open={!!drawerId}
      onClose={closeDrawer}
      width={480}
      title={release ? <Text style={{ fontFamily: 'var(--mono)', color: 'var(--accent)' }}>{release.id}</Text> : null}
      footer={
        release && (
          <Space wrap>
            {next && (
              <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => advance(release.id)}>
                {STATUS_ACTION[release.status]}
              </Button>
            )}
            <Button icon={<EditOutlined />} onClick={() => openModal({ type: 'release', id: release.id })}>
              编辑
            </Button>
            {canFail && (
              <Button danger icon={<AlertOutlined />} onClick={() => setStatus('failed')}>
                标记失败
              </Button>
            )}
            {canCancel && <Button onClick={() => setStatus('cancelled')}>取消</Button>}
            <Button danger icon={<DeleteOutlined />} onClick={() => remove([release.id])} aria-label="删除发布单" />
          </Space>
        )
      }
    >
      {release && (
        <>
          <Title level={4} style={{ marginTop: 0 }}>
            {release.title}
          </Title>
          <Space wrap style={{ marginBottom: 16 }}>
            <StatusTag status={release.status} />
            <PlatformTag platform={release.platform} />
            <EnvTag env={release.env} />
          </Space>
          {release.status === 'building' && (
            <Progress percent={release.progress} status="active" strokeColor={statusColor(release.status)} style={{ marginBottom: 16 }} />
          )}
          <Descriptions
            size="small"
            column={2}
            bordered
            items={[
              { key: 'app', label: '应用', children: app?.name || release.appId },
              { key: 'version', label: '版本', children: `v${release.version}` },
              { key: 'owner', label: '负责人', children: release.owner },
              { key: 'priority', label: '优先级', children: `P${release.priority}` },
              { key: 'created', label: '创建时间', children: fmtFull(release.createdAt) },
              { key: 'updated', label: '更新时间', children: fmtFull(release.updatedAt) },
            ]}
          />

          <SectionLabel>备注</SectionLabel>
          <Paragraph style={{ marginBottom: 0 }}>{release.notes || '暂无备注'}</Paragraph>

          <SectionLabel>产物</SectionLabel>
          {release.artifacts.length ? (
            <Space direction="vertical" style={{ width: '100%' }}>
              {release.artifacts.map((a) => (
                <div
                  key={a.name}
                  style={{ display: 'flex', alignItems: 'center', gap: 12, padding: 12, borderRadius: 12, border: '1px solid var(--border)', background: 'var(--surface-2)' }}
                >
                  <FileZipOutlined style={{ color: 'var(--accent)', fontSize: 18 }} />
                  <div style={{ flex: 1 }}>
                    <div style={{ fontFamily: 'var(--mono)', fontWeight: 650, fontSize: 12.5 }}>{a.name}</div>
                    <Text type="secondary" style={{ fontSize: 11 }}>{a.size}</Text>
                  </div>
                  <Button size="small" type="text" icon={<CopyOutlined />} onClick={() => copy(a.name)} aria-label={`复制产物名 ${a.name}`} />
                </div>
              ))}
            </Space>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="构建完成后生成产物" />
          )}

          <SectionLabel>时间线</SectionLabel>
          <Timeline
            items={[...release.timeline].reverse().map((t) => ({
              color: 'var(--accent)',
              children: (
                <>
                  <div style={{ fontWeight: 650, fontSize: 13 }}>{t.action}</div>
                  <Text type="secondary" style={{ fontSize: 11.5 }}>
                    {t.by} · {fmtFull(t.at)}
                  </Text>
                </>
              ),
            }))}
          />
        </>
      )}
    </Drawer>
  );
}