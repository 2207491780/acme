import { App, Button, Card, Col, Descriptions, Row, Space, Typography } from 'antd';
import { ApartmentOutlined, AppstoreOutlined, CopyOutlined, EditOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { Platform } from '@acme/core';
import { useStore } from '../store/useStore';
import { HealthTag, PlatformIcon } from '../components/atoms';
import { PageHead } from '../components/PageHead';
import { copyText } from '../utils/dom';

const { Text, Paragraph } = Typography;

export function Apps() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const apps = useStore((s) => s.state.apps);
  const releases = useStore((s) => s.state.releases);
  const setQuery = useStore((s) => s.setQuery);
  const openModal = useStore((s) => s.openModal);

  const copy = async (text: string) => {
    const ok = await copyText(text);
    message[ok ? 'success' : 'error'](ok ? `已复制：${text}` : '复制失败，请手动复制');
  };

  const viewReleases = (platform: Platform) => {
    setQuery({ platform, status: 'all' });
    navigate('/releases');
  };

  return (
    <>
      <PageHead
        eyebrow="Applications"
        title="应用与工程结构"
        sub="pnpm monorepo：共享 core 业务层，Web 为页面唯一来源，Electron 复用 Web 产物，React Native 共享 core。"
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
                extra={<Button size="small" icon={<EditOutlined />} onClick={() => openModal({ type: 'app', id: app.id })} aria-label={`编辑应用 ${app.name}`} />}
              >
                <Paragraph type="secondary" style={{ fontSize: 13 }}>{app.description}</Paragraph>
                <Descriptions
                  size="small"
                  column={1}
                  items={[
                    { key: 'v', label: '当前版本', children: `v${app.version}` },
                    { key: 'o', label: '负责人', children: app.owner },
                    { key: 'h', label: '健康度', children: <HealthTag health={app.health} /> },
                    { key: 'c', label: '发布单', children: `${count} 个` },
                  ]}
                />
                <Space style={{ marginTop: 16 }}>
                  <Button size="small" icon={<CopyOutlined />} onClick={() => copy(app.path)}>复制路径</Button>
                  <Button size="small" type="text" icon={<UnorderedListOutlined />} onClick={() => viewReleases(app.platform)}>查看发布</Button>
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Row gutter={[18, 18]}>
        <Col xs={24} lg={12}>
          <Card title={<Space><ApartmentOutlined style={{ color: 'var(--accent)' }} /> 复用关系</Space>} style={{ height: '100%' }}>
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              <Space><PlatformIcon platform="web" size={18} /><div><Text strong>Web 端</Text><div className="platform-path">React + Vite + Ant Design · 页面与业务逻辑唯一来源</div></div></Space>
              <Text type="secondary" style={{ fontSize: 12, paddingLeft: 18 }}>↓ 开发加载 Dev Server · 打包加载构建输出</Text>
              <Space><PlatformIcon platform="desktop" size={18} /><div><Text strong>Electron 桌面端</Text><div className="platform-path">主进程 / 预加载桥接 · 复用 Web 产物</div></div></Space>
              <Text type="secondary" style={{ fontSize: 12, paddingLeft: 18 }}>↓ 共享 @acme/core 业务层</Text>
              <Space><PlatformIcon platform="mobile" size={18} /><div><Text strong>移动端</Text><div className="platform-path">React Native + Expo · 共享 core</div></div></Space>
            </Space>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card
            title={<Space><AppstoreOutlined style={{ color: 'var(--accent)' }} /> 目录结构</Space>}
            extra={<Button size="small" type="text" icon={<CopyOutlined />} onClick={() => copy('workspace/apps/frontend')}>复制</Button>}
            style={{ height: '100%' }}
          >
            <pre className="tree">
{`frontend/                  `}<span className="note"># pnpm monorepo</span>{`
├── `}<span className="dir">packages/core/</span>{`         `}<span className="note"># 共享类型/业务/状态（框架无关 TS）</span>{`
├── `}<span className="dir">web/</span>{`                   `}<span className="note"># React + Vite + Ant Design</span>{`
│   ├── src/{components,pages,store,hooks,utils,styles}
│   └── tests/
├── `}<span className="dir">desktop/</span>{`               `}<span className="note"># Electron 复用 web 产物</span>{`
│   └── src/{main,preload,shared}
└── `}<span className="dir">mobile/</span>{`                `}<span className="note"># React Native + Expo</span>{`
    └── src/{screens,components,store}`}
            </pre>
          </Card>
        </Col>
      </Row>
    </>
  );
}