import type { ReactNode } from 'react';
import { App, Button, Descriptions, Drawer, Empty, Progress, Space, Timeline, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  AlertOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  FileZipOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { NEXT_STATUS, fmtFull, statusColor, type Status } from '@acme/core';
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
  const { t } = useTranslation();
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
    message[ok ? 'success' : 'error'](ok ? t('apps.copy.done', { text }) : t('apps.copy.failed'));
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
                {t(`releases.action.${release.status}`)}
              </Button>
            )}
            <Button icon={<EditOutlined />} onClick={() => openModal({ type: 'release', id: release.id })}>
              {t('releases.drawer.edit')}
            </Button>
            {canFail && (
              <Button danger icon={<AlertOutlined />} onClick={() => setStatus('failed')}>
                {t('releases.drawer.markFailed')}
              </Button>
            )}
            {canCancel && <Button onClick={() => setStatus('cancelled')}>{t('releases.drawer.cancel')}</Button>}
            <Button danger icon={<DeleteOutlined />} onClick={() => remove([release.id])} aria-label={t('releases.drawer.delete')} />
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
              { key: 'app', label: t('releases.drawer.app'), children: app?.name || release.appId },
              { key: 'version', label: t('releases.drawer.version'), children: `v${release.version}` },
              { key: 'owner', label: t('releases.drawer.owner'), children: release.owner },
              { key: 'priority', label: t('releases.drawer.priority'), children: `P${release.priority}` },
              { key: 'created', label: t('releases.drawer.createdAt'), children: fmtFull(release.createdAt) },
              { key: 'updated', label: t('releases.drawer.updatedAt'), children: fmtFull(release.updatedAt) },
            ]}
          />

          <SectionLabel>{t('releases.drawer.notes')}</SectionLabel>
          <Paragraph style={{ marginBottom: 0 }}>{release.notes || t('releases.drawer.notesEmpty')}</Paragraph>

          <SectionLabel>{t('releases.drawer.artifacts')}</SectionLabel>
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
                  <Button size="small" type="text" icon={<CopyOutlined />} onClick={() => copy(a.name)} aria-label={t('releases.drawer.artifactCopy', { name: a.name })} />
                </div>
              ))}
            </Space>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('releases.drawer.artifactsEmpty')} />
          )}

          <SectionLabel>{t('releases.drawer.timeline')}</SectionLabel>
          <Timeline
            items={[...release.timeline].reverse().map((item) => ({
              color: 'var(--accent)',
              children: (
                <>
                  <div style={{ fontWeight: 650, fontSize: 13 }}>{item.action}</div>
                  <Text type="secondary" style={{ fontSize: 11.5 }}>
                    {item.by} · {fmtFull(item.at)}
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
