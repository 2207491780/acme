import { Button, Card, Col, List, Row, Space, Typography } from 'antd';
import {
  AppstoreOutlined,
  ClockCircleOutlined,
  DashboardOutlined,
  PlusOutlined,
  RocketOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  STATUSES,
  metrics,
  relTime,
  sortReleases,
  statusLabel,
  statusTone,
  type Platform,
  type Status,
  type Tone,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { HealthTag, PlatformIcon, PlatformTag, StatusTag } from '../components/atoms';

const { Text, Paragraph } = Typography;

const TONE_VAR: Record<Tone, string> = {
  success: 'var(--acid)',
  warning: 'var(--amber)',
  danger: 'var(--red)',
  info: 'var(--blue)',
  violet: 'var(--violet)',
  muted: 'var(--slate)',
};

const STAGES: [Status, string][] = [
  ['draft', '草稿'],
  ['building', '构建'],
  ['testing', '测试'],
  ['approved', '审批'],
  ['published', '发布'],
];

export function Dashboard() {
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const setQuery = useStore((s) => s.setQuery);
  const openDrawer = useStore((s) => s.openDrawer);
  const openModal = useStore((s) => s.openModal);

  const m = metrics(state);
  const recent = sortReleases(state.releases, 'updated', state.order).slice(0, 5);
  const maxStatus = Math.max(1, ...Object.values(m.byStatus));

  const goPlatform = (platform: Platform) => {
    setQuery({ platform, status: 'all' });
    navigate('/releases');
  };
  const goStatus = (status: Status) => {
    setQuery({ status, platform: 'all' });
    navigate('/releases');
  };

  return (
    <>
      <section className="hero">
        <div className="hero-grid">
          <div>
            <div className="eyebrow">ACME · Frontend Delivery</div>
            <h1>多端交付，一屏掌控</h1>
            <p>
              统一管理 Web、Electron 桌面端与移动端的发布单。桌面端复用 Web 构建产物，移动端共享业务层，从构建、测试、审批到发布全程可追踪。
            </p>
            <Space wrap>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ type: 'release', id: null })}>
                新建发布单
              </Button>
              <Button icon={<UnorderedListOutlined />} onClick={() => navigate('/releases')}>
                查看全部发布
              </Button>
              <Button type="text" icon={<AppstoreOutlined />} onClick={() => navigate('/apps')}>
                应用结构
              </Button>
            </Space>
          </div>
          <div className="hero-stats">
            <div className="hero-stat"><div className="n">{m.active}</div><div className="l">进行中</div></div>
            <div className="hero-stat"><div className="n">{m.approved}</div><div className="l">待发布</div></div>
            <div className="hero-stat"><div className="n">{m.publishedToday}</div><div className="l">今日发布</div></div>
            <div className="hero-stat"><div className="n">{m.failed}</div><div className="l">失败待处理</div></div>
          </div>
        </div>
      </section>

      <Row gutter={[18, 18]} style={{ marginBottom: 18 }}>
        {state.apps.map((app) => {
          const latest = sortReleases(state.releases.filter((r) => r.appId === app.id), 'updated', state.order)[0];
          return (
            <Col xs={24} md={12} xl={8} key={app.id}>
              <Card hoverable onClick={() => goPlatform(app.platform)} style={{ height: '100%' }}>
                <Space align="start" style={{ marginBottom: 12 }}>
                  <PlatformIcon platform={app.platform} />
                  <div>
                    <Text strong>{app.name}</Text>
                    <div className="platform-path">{app.path}</div>
                  </div>
                </Space>
                <Paragraph type="secondary" style={{ fontSize: 13, minHeight: 42 }}>{app.description}</Paragraph>
                <Space wrap>
                  <HealthTag health={app.health} />
                  <Text style={{ color: 'var(--accent)' }}>v{app.version}</Text>
                  {latest ? <StatusTag status={latest.status} /> : <Text type="secondary">暂无发布</Text>}
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Card
        style={{ marginBottom: 18 }}
        title={<Space><RocketOutlined style={{ color: 'var(--accent)' }} /> 交付流水线</Space>}
        extra={<Text type="secondary">共 {m.total} 个发布单</Text>}
      >
        <div className="pipeline">
          {STAGES.map(([key, label]) => (
            <button key={key} className={`stage s-${key}`} onClick={() => goStatus(key)}>
              <div className="n">{m.byStatus[key] || 0}</div>
              <div className="l">{label}</div>
            </button>
          ))}
        </div>
      </Card>

      <Row gutter={[18, 18]}>
        <Col xs={24} xl={16}>
          <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <Card title={<Space><DashboardOutlined style={{ color: 'var(--accent)' }} /> 发布状态分布</Space>}>
              <div className="bars">
                {STATUSES.map((s) => (
                  <div className="bar-row" key={s}>
                    <span className="bar-label">{statusLabel(s)}</span>
                    <div className="bar-track">
                      <div
                        className="bar-fill"
                        style={{ width: `${((m.byStatus[s] || 0) / maxStatus) * 100}%`, background: TONE_VAR[statusTone(s)] }}
                      />
                    </div>
                    <span className="bar-value">{m.byStatus[s] || 0}</span>
                  </div>
                ))}
              </div>
            </Card>
            <Card
              title={<Space><ClockCircleOutlined style={{ color: 'var(--accent)' }} /> 最近更新</Space>}
              extra={<Button size="small" type="text" onClick={() => navigate('/releases')}>全部</Button>}
            >
              <List
                dataSource={recent}
                renderItem={(r) => (
                  <List.Item style={{ cursor: 'pointer' }} onClick={() => openDrawer(r.id)}>
                    <List.Item.Meta
                      title={<Text strong>{r.title} · {r.id}</Text>}
                      description={
                        <Space wrap size={4}>
                          <PlatformTag platform={r.platform} />
                          <StatusTag status={r.status} />
                          <Text type="secondary" style={{ fontSize: 11 }}>{relTime(r.updatedAt)}</Text>
                        </Space>
                      }
                    />
                  </List.Item>
                )}
              />
            </Card>
          </Space>
        </Col>
        <Col xs={24} xl={8}>
          <Card title={<Space><ThunderboltOutlined style={{ color: 'var(--accent)' }} /> 动态</Space>}>
            <List
              dataSource={state.activity.slice(0, 14)}
              renderItem={(a) => (
                <List.Item>
                  <List.Item.Meta
                    avatar={<span style={{ display: 'inline-block', width: 9, height: 9, borderRadius: '50%', marginTop: 6, background: TONE_VAR[a.tone] || TONE_VAR.info }} />}
                    title={<Text style={{ fontSize: 13 }}>{a.text}</Text>}
                    description={<Text type="secondary" style={{ fontSize: 11 }}>{relTime(a.at)}</Text>}
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>
      </Row>
    </>
  );
}