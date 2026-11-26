import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Empty, List, Row, Space, Spin, Typography } from 'antd';
import {
  ApartmentOutlined,
  AppstoreOutlined,
  AuditOutlined,
  FolderOpenOutlined,
  GlobalOutlined,
  LineChartOutlined,
  NotificationOutlined,
  PieChartOutlined,
  ReloadOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  notifyApi,
  relTime,
  useMeStore,
  type AdminNoticeView,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { HealthTag, PlatformIcon } from '../components/atoms';
import { Chart } from '../components/Chart';
import { useDashboardCharts, type DashboardChartStats } from '../hooks/useDashboardCharts';
import { useDashboardSummary } from '../hooks/useDashboardSummary';

const { Text } = Typography;

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
  const apps = useStore((s) => s.state.apps);
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
  // （登录趋势/审计动作分布）；无 summary（加载中/失败）→ undefined 保持空态，无本地业务演示数据源。
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

      {/* 多端基座清单（REQ-20261126-125836 保留）：Web / Electron / Mobile 三端与共享 core 的入口 */}
      <Row gutter={[18, 18]} style={{ marginBottom: 18 }}>
        {apps.map((app) => (
          <Col xs={24} md={12} xl={8} key={app.id}>
            <Card hoverable onClick={() => navigate('/apps')} style={{ height: '100%' }}>
              <Space align="start" style={{ marginBottom: 12 }}>
                <PlatformIcon platform={app.platform} />
                <div>
                  <Text strong>{app.name}</Text>
                  <div className="platform-path">{app.path}</div>
                </div>
              </Space>
              <Typography.Paragraph type="secondary" style={{ fontSize: 13, minHeight: 42 }}>{app.description}</Typography.Paragraph>
              <Space wrap>
                <HealthTag health={app.health} />
                <Text style={{ color: 'var(--accent)' }}>v{app.version}</Text>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

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
    </>
  );
}
