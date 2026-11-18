import { App, Button, Card, Col, Descriptions, Row, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApartmentOutlined, AppstoreOutlined, CopyOutlined, EditOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { Platform } from '@acme/core';
import { useStore } from '../store/useStore';
import { HealthTag, PlatformIcon } from '../components/atoms';
import { PageHead } from '../components/PageHead';
import { copyText } from '../utils/dom';

const { Text, Paragraph } = Typography;

/** 目录结构树（文案经 i18n apps.tree.* 承载，含各级注释）。 */
function DirectoryTree() {
  const { t } = useTranslation();
  return (
    <pre className="tree">
{`frontend/                  `}<span className="note">{t('apps.tree.frontendNote')}</span>{`
├── `}<span className="dir">packages/core/</span>{`         `}<span className="note">{t('apps.tree.coreNote')}</span>{`
├── `}<span className="dir">web/</span>{`                   `}<span className="note">{t('apps.tree.webNote')}</span>{`
│   ├── src/{components,pages,store,hooks,utils,styles}
│   └── tests/
├── `}<span className="dir">desktop/</span>{`               `}<span className="note">{t('apps.tree.desktopNote')}</span>{`
│   └── src/{main,preload,shared}
└── `}<span className="dir">mobile/</span>{`                `}<span className="note">{t('apps.tree.mobileNote')}</span>{`
    └── src/{screens,components,store}`}
    </pre>
  );
}

export function Apps() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const apps = useStore((s) => s.state.apps);
  const releases = useStore((s) => s.state.releases);
  const setQuery = useStore((s) => s.setQuery);
  const openModal = useStore((s) => s.openModal);

  const copy = async (text: string) => {
    const ok = await copyText(text);
    message[ok ? 'success' : 'error'](ok ? t('apps.copy.done', { text }) : t('apps.copy.failed'));
  };

  const viewReleases = (platform: Platform) => {
    setQuery({ platform, status: 'all' });
    navigate('/releases');
  };

  return (
    <>
      <PageHead
        eyebrow={t('apps.head.eyebrow')}
        title={t('apps.head.title')}
        sub={t('apps.head.sub')}
      />

      <Row gutter={[18, 18]} style={{ marginBottom: 18 }}>
        {apps.map((app) => {
          const count = releases.filter((r) => r.appId === app.id).length;
          return (
            <Col xs={24} md={12} xl={8} key={app.id}>
              <Card
                style={{ height: '100%' }}
                title={
                  <Space align="start">
                    <PlatformIcon platform={app.platform} />
                    <div>
                      <Text strong>{app.name}</Text>
                      <div className="platform-path">{app.path}</div>
                    </div>
                  </Space>
                }
                extra={<Button size="small" icon={<EditOutlined />} onClick={() => openModal({ type: 'app', id: app.id })} aria-label={t('apps.card.editApp', { name: app.name })} />}
              >
                <Paragraph type="secondary" style={{ fontSize: 13 }}>{app.description}</Paragraph>
                <Descriptions
                  size="small"
                  column={1}
                  items={[
                    { key: 'v', label: t('apps.card.currentVersion'), children: `v${app.version}` },
                    { key: 'o', label: t('apps.card.owner'), children: app.owner },
                    { key: 'h', label: t('apps.card.health'), children: <HealthTag health={app.health} /> },
                    { key: 'c', label: t('apps.card.releases'), children: t('apps.card.releaseCount', { n: count }) },
                  ]}
                />
                <Space style={{ marginTop: 16 }}>
                  <Button size="small" icon={<CopyOutlined />} onClick={() => copy(app.path)}>{t('apps.card.copyPath')}</Button>
                  <Button size="small" type="text" icon={<UnorderedListOutlined />} onClick={() => viewReleases(app.platform)}>{t('apps.card.viewReleases')}</Button>
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Row gutter={[18, 18]}>
        <Col xs={24} lg={12}>
          <Card title={<Space><ApartmentOutlined style={{ color: 'var(--accent)' }} /> {t('apps.reuse.title')}</Space>} style={{ height: '100%' }}>
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              <Space><PlatformIcon platform="web" size={18} /><div><Text strong>{t('apps.reuse.web')}</Text><div className="platform-path">{t('apps.reuse.webDesc')}</div></div></Space>
              <Text type="secondary" style={{ fontSize: 12, paddingLeft: 18 }}>{t('apps.reuse.webArrow')}</Text>
              <Space><PlatformIcon platform="desktop" size={18} /><div><Text strong>{t('apps.reuse.desktop')}</Text><div className="platform-path">{t('apps.reuse.desktopDesc')}</div></div></Space>
              <Text type="secondary" style={{ fontSize: 12, paddingLeft: 18 }}>{t('apps.reuse.coreArrow')}</Text>
              <Space><PlatformIcon platform="mobile" size={18} /><div><Text strong>{t('apps.reuse.mobile')}</Text><div className="platform-path">{t('apps.reuse.mobileDesc')}</div></div></Space>
            </Space>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card
            title={<Space><AppstoreOutlined style={{ color: 'var(--accent)' }} /> {t('apps.tree.title')}</Space>}
            extra={<Button size="small" type="text" icon={<CopyOutlined />} onClick={() => copy(t('apps.tree.copyTarget'))}>{t('apps.tree.copy')}</Button>}
            style={{ height: '100%' }}
          >
            <DirectoryTree />
          </Card>
        </Col>
      </Row>
    </>
  );
}
