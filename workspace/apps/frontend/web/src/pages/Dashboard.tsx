import { Button, Card, Col, List, Row, Space, Typography } from 'antd';
import {
  AppstoreOutlined,
  ClockCircleOutlined,
  DashboardOutlined,
  FolderOpenOutlined,
  LineChartOutlined,
  PieChartOutlined,
  PlusOutlined,
  RocketOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  STATUSES,
  metrics,
  relTime,
  sortReleases,
  statusTone,
  useMeStore,
  type Platform,
  type Status,
  type Tone,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { HealthTag, PlatformIcon, PlatformTag, StatusTag } from '../components/atoms';
import { Chart } from '../components/Chart';
import { useDashboardCharts } from '../hooks/useDashboardCharts';

const { Text, Paragraph } = Typography;

const TONE_VAR: Record<Tone, string> = {
  success: 'var(--acid)',
  warning: 'var(--amber)',
  danger: 'var(--red)',
  info: 'var(--blue)',
  violet: 'var(--violet)',
  muted: 'var(--slate)',
};

/** 流水线阶段（状态子集 + 展示名经 i18n dashboard.stages 承载）。 */
const STAGES: Status[] = ['draft', 'building', 'testing', 'approved', 'published'];

export function Dashboard() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const setQuery = useStore((s) => s.setQuery);
  const openDrawer = useStore((s) => s.openDrawer);
  const openModal = useStore((s) => s.openModal);
  // 文件管理入口（REQ-20261114-120101）：按权限显隐（fail-closed），路由仍由 /files 权限门禁守护
  const canFiles = useMeStore((s) => (s.me?.permissions ?? []).includes('infra:file:list'));

  const m = metrics(state);
  const recent = sortReleases(state.releases, 'updated', state.order).slice(0, 5);
  const maxStatus = Math.max(1, ...Object.values(m.byStatus));
  // 图表区（REQ-20261114-120112 AC-1）：数据来自统一数据 hook（Dashboard 既有统计衍生）
  const charts = useDashboardCharts();

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
            <div className="eyebrow">{t('dashboard.hero.eyebrow')}</div>
            <h1>{t('dashboard.hero.title')}</h1>
            <p>
              {t('dashboard.hero.desc')}
            </p>
            <Space wrap>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ type: 'release', id: null })}>
                {t('dashboard.actions.createRelease')}
              </Button>
              <Button icon={<UnorderedListOutlined />} onClick={() => navigate('/releases')}>
                {t('dashboard.actions.viewAll')}
              </Button>
              <Button type="text" icon={<AppstoreOutlined />} onClick={() => navigate('/apps')}>
                {t('dashboard.actions.apps')}
              </Button>
              {canFiles ? (
                <Button type="text" icon={<FolderOpenOutlined />} onClick={() => navigate('/files')}>
                  {t('dashboard.actions.files')}
                </Button>
              ) : null}
            </Space>
          </div>
          <div className="hero-stats">
            <div className="hero-stat"><div className="n">{m.active}</div><div className="l">{t('dashboard.stats.active')}</div></div>
            <div className="hero-stat"><div className="n">{m.approved}</div><div className="l">{t('dashboard.stats.approved')}</div></div>
            <div className="hero-stat"><div className="n">{m.publishedToday}</div><div className="l">{t('dashboard.stats.publishedToday')}</div></div>
            <div className="hero-stat"><div className="n">{m.failed}</div><div className="l">{t('dashboard.stats.failed')}</div></div>
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
                  {latest ? <StatusTag status={latest.status} /> : <Text type="secondary">{t('dashboard.noRelease')}</Text>}
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Card
        style={{ marginBottom: 18 }}
        title={<Space><RocketOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.cards.pipeline')}</Space>}
        extra={<Text type="secondary">{t('dashboard.cards.pipelineTotal', { total: m.total })}</Text>}
      >
        <div className="pipeline">
          {STAGES.map((key) => (
            <button key={key} className={`stage s-${key}`} onClick={() => goStatus(key)}>
              <div className="n">{m.byStatus[key] || 0}</div>
              <div className="l">{t(`dashboard.stages.${key}`)}</div>
            </button>
          ))}
        </div>
      </Card>

      <Row gutter={[18, 18]} style={{ marginBottom: 18 }}>
        <Col xs={24} xl={12}>
          <Card
            title={<Space><LineChartOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.cards.trendChart')}</Space>}
            style={{ height: '100%' }}
          >
            <Chart option={charts.trend ?? undefined} />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card
            title={<Space><PieChartOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.cards.distributionChart')}</Space>}
            style={{ height: '100%' }}
          >
            <Chart option={charts.distribution ?? undefined} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[18, 18]}>
        <Col xs={24} xl={16}>
          <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <Card title={<Space><DashboardOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.cards.statusDistribution')}</Space>}>
              <div className="bars">
                {STATUSES.map((s) => (
                  <div className="bar-row" key={s}>
                    <span className="bar-label">{t(`releases.status.${s}`)}</span>
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
              title={<Space><ClockCircleOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.cards.recent')}</Space>}
              extra={<Button size="small" type="text" onClick={() => navigate('/releases')}>{t('dashboard.cards.recentAll')}</Button>}
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
          <Card title={<Space><ThunderboltOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.cards.activity')}</Space>}>
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
