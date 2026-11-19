import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Empty, List, Row, Space, Spin, Typography } from 'antd';
import {
  ApartmentOutlined,
  AppstoreOutlined,
  AuditOutlined,
  ClockCircleOutlined,
  DashboardOutlined,
  FolderOpenOutlined,
  GlobalOutlined,
  LineChartOutlined,
  NotificationOutlined,
  PieChartOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  STATUSES,
  metrics,
  notifyApi,
  relTime,
  sortReleases,
  statusTone,
  useMeStore,
  type AdminNoticeView,
  type Platform,
  type Status,
  type Tone,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { HealthTag, PlatformIcon, PlatformTag, StatusTag } from '../components/atoms';
import { Chart } from '../components/Chart';
import { useDashboardCharts, type DashboardChartStats } from '../hooks/useDashboardCharts';
import { useDashboardSummary } from '../hooks/useDashboardSummary';

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

/** 快捷入口（REQ-20261119-011524 契约④⑤）：显隐走 Auth 原语（me.permissions，fail-closed），
 *  跳转仍由各路由的权限门禁守护；文案复用 nav.items.*（与导航同一目的地同一叫法）。 */
const QUICK_ACCESS = [
  { perm: 'system:user:list', path: '/users', icon: TeamOutlined, labelKey: 'nav.items.users' },
  { perm: 'system:tenant:list', path: '/tenants', icon: ApartmentOutlined, labelKey: 'nav.items.tenants' },
  { perm: 'infra:audit:list', path: '/audit', icon: AuditOutlined, labelKey: 'nav.items.audit' },
  { perm: 'infra:file:list', path: '/files', icon: FolderOpenOutlined, labelKey: 'nav.items.files' },
];

/** 后端趋势日期（YYYY-MM-DD）→ 图表短标签（M/D，对齐本地趋势标签口径）；异常格式原样透传。 */
function shortTrendLabel(date: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  return m ? `${Number(m[2])}/${Number(m[3])}` : date;
}

export function Dashboard() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const setQuery = useStore((s) => s.setQuery);
  const openDrawer = useStore((s) => s.openDrawer);
  const openModal = useStore((s) => s.openModal);
  // 权限感知（Auth 原语 me.permissions，fail-closed）：hero 文件入口与首页 widget 显隐共用同一来源
  const mePermissions = useMeStore((s) => s.me?.permissions);
  const hasPermission = (perm: string): boolean => (mePermissions ?? []).includes(perm);
  // 文件管理入口（REQ-20261114-120101）：按权限显隐（fail-closed），路由仍由 /files 权限门禁守护
  const canFiles = hasPermission('infra:file:list');
  // 已发布公告 widget（REQ-20261119-011524）：公告列表为入口型 widget，按域 list 权限显隐（契约①）
  const canNotices = hasPermission('system:notice:list');
  // 快捷入口（契约④⑤）：Auth 原语按各域 list 权限显隐，跳转仍由路由权限门禁守护
  const quickEntries = QUICK_ACCESS.filter((entry) => hasPermission(entry.perm));
  // 平台概览聚合（REQ-20261119-011524）：挂载即取 + 手动刷新（15min stale 标记）；未取到按 '—' 降级
  const summary = useDashboardSummary();
  // 已发布公告 widget：page1 size5 拉取 + 客户端过滤 PUBLISHED 取 5 条（端点不加参数，契约⑤）
  const [publishedNotices, setPublishedNotices] = useState<AdminNoticeView[] | null>(null);
  useEffect(() => {
    if (!canNotices) {
      return undefined;
    }
    let cancelled = false;
    notifyApi.noticeList(1, 5)
      .then((page) => {
        if (!cancelled) {
          setPublishedNotices(page.records.filter((n) => n.status === 'PUBLISHED').slice(0, 5));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setPublishedNotices([]); // 拉取失败按空态降级，不阻塞首页
        }
      });
    return () => {
      cancelled = true;
    };
  }, [canNotices]);

  const m = metrics(state);
  const recent = sortReleases(state.releases, 'updated', state.order).slice(0, 5);
  const maxStatus = Math.max(1, ...Object.values(m.byStatus));
  // 平台概览六卡（契约⑤）：数字走 summary；未取到（加载中/失败）→ '—'；近14日登录 = 趋势桶合计
  const overviewCards = [
    { key: 'users', value: summary.data?.users },
    { key: 'roles', value: summary.data?.roles },
    { key: 'tenants', value: summary.data?.tenants },
    { key: 'online', value: summary.data?.onlineUsers },
    { key: 'unread', value: summary.data?.unreadMessages },
    {
      key: 'logins14d',
      value: summary.data
        ? summary.data.loginTrend.reduce((n, p) => n + p.count, 0)
        : undefined,
    },
  ];
  // 图表区（REQ-20261114-120112 AC-1 + REQ-20261119-011524 契约⑤）：有 summary → 后端数据源
  // （登录趋势/审计动作分布，经 useDashboardCharts stats props 既有预留位接入）；
  // 无 summary（加载中/失败）→ undefined 保持本地 release 数据源降级
  const chartStats = useMemo<DashboardChartStats | undefined>(() => {
    const data = summary.data;
    if (!data) {
      return undefined;
    }
    return {
      trend: {
        labels: data.loginTrend.map((p) => shortTrendLabel(p.date)),
        values: data.loginTrend.map((p) => p.count),
      },
      distribution: {
        items: data.auditTopActions.map((p) => ({ name: p.action, value: p.count })),
      },
    };
  }, [summary.data]);
  const charts = useDashboardCharts(chartStats);

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

      <Card
        style={{ marginBottom: 18 }}
        title={<Space><GlobalOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.overview.title')}</Space>}
        extra={(
          <Space size={8}>
            {summary.stale && summary.data ? (
              <Text type="secondary" style={{ fontSize: 12 }}>{t('dashboard.overview.stale')}</Text>
            ) : null}
            <Button
              size="small"
              type="text"
              icon={<ReloadOutlined />}
              loading={summary.loading}
              onClick={() => void summary.refresh()}
            >
              {t('dashboard.overview.refresh')}
            </Button>
          </Space>
        )}
      >
        <div className="overview-grid">
          {overviewCards.map((card) => (
            <div className="overview-stat" key={card.key}>
              <div className="n">{card.value === undefined ? '—' : card.value}</div>
              <div className="l">{t(`dashboard.overview.${card.key}`)}</div>
            </div>
          ))}
        </div>
      </Card>

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

      {canNotices || quickEntries.length > 0 ? (
        <Row gutter={[18, 18]} style={{ marginBottom: 18 }}>
          {canNotices ? (
            <Col xs={24} xl={16}>
              <Card
                title={<Space><NotificationOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.notices.title')}</Space>}
                style={{ height: '100%' }}
                extra={(
                  <Button size="small" type="text" onClick={() => navigate('/notices')}>
                    {t('dashboard.notices.viewAll')}
                  </Button>
                )}
              >
                {publishedNotices === null ? (
                  <Spin style={{ display: 'block', margin: '48px auto' }} />
                ) : publishedNotices.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('dashboard.notices.empty')} />
                ) : (
                  <List
                    dataSource={publishedNotices}
                    renderItem={(n) => (
                      <List.Item style={{ cursor: 'pointer' }} onClick={() => navigate('/notices')}>
                        <List.Item.Meta
                          title={<Text strong>{n.title}</Text>}
                          description={<Text type="secondary" style={{ fontSize: 11 }}>{relTime(n.publishAt ?? n.createdAt)}</Text>}
                        />
                      </List.Item>
                    )}
                  />
                )}
              </Card>
            </Col>
          ) : null}
          {quickEntries.length > 0 ? (
            <Col xs={24} xl={canNotices ? 8 : 24}>
              <Card
                title={<Space><AppstoreOutlined style={{ color: 'var(--accent)' }} /> {t('dashboard.quickAccess.title')}</Space>}
                style={{ height: '100%' }}
              >
                <Space wrap size={12}>
                  {quickEntries.map((entry) => (
                    <Button key={entry.path} icon={<entry.icon />} onClick={() => navigate(entry.path)}>
                      {t(entry.labelKey)}
                    </Button>
                  ))}
                </Space>
              </Card>
            </Col>
          ) : null}
        </Row>
      ) : null}

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
