import { Link } from 'react-router-dom';
import { Button, Card, Descriptions, Space, Tag, Typography } from 'antd';
import {
  ArrowRightOutlined,
  AuditOutlined,
  DownloadOutlined,
  FileZipOutlined,
  HistoryOutlined,
  RocketOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { fmtFull, statusLabel, statusTone, platformShort, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** A3 发布单详情全屏页：比正式抽屉更完整的信息架构（产物/关联/风险/回滚入口）。 */
export default function ReleaseDetail() {
  const releases = useStore((s) => s.state.releases);
  const release = releases[0];
  const app = useStore((s) => s.state.apps.find((a) => a.id === releases[0]?.appId));
  const related = releases.filter((r) => r.appId === release?.appId && r.id !== release?.id).slice(0, 4);

  if (!release) return <EmptyState title="暂无发布单" />;

  return (
    <PageShell
      title={`${release.title}（详情全屏）`}
      domain="发布单域 A3"
      desc="全屏详情信息架构原型：完整时间线、产物、关联发布与风险面板；抽屉保留为快速预览，复杂操作引导至此。"
      extra={
        <Space>
          <Link to={`/prototype/approval?release=${release.id}`}>
            <Button icon={<AuditOutlined />}>审批详情</Button>
          </Link>
          <Link to={`/prototype/build-log?release=${release.id}`}>
            <Button icon={<HistoryOutlined />}>构建日志</Button>
          </Link>
        </Space>
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card size="small">
            <Space wrap size={8} style={{ marginBottom: 12 }}>
              <Tag color={TONE_ANTD[statusTone(release.status)]}>{statusLabel(release.status)}</Tag>
              <Tag>{platformShort(release.platform)}</Tag>
              <Text strong style={{ fontFamily: 'var(--mono)' }}>{release.id}</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>v{release.version} · {release.owner}</Text>
            </Space>
            <Descriptions size="small" bordered column={2}
              items={[
                { key: 'env', label: '目标环境', children: release.env },
                { key: 'prio', label: '优先级', children: `P${release.priority}` },
                { key: 'created', label: '创建时间', children: fmtFull(release.createdAt) },
                { key: 'updated', label: '最近更新', children: fmtFull(release.updatedAt) },
                { key: 'app', label: '所属应用', children: app?.name ?? release.appId },
                { key: 'appver', label: '应用当前版本', children: app ? `v${app.version}` : '—' },
              ]}
            />
            <div style={{ marginTop: 12 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>备注</Text>
              <p style={{ margin: '4px 0 0', fontSize: 13 }}>{release.notes || '暂无备注'}</p>
            </div>
          </Card>

          <Section
            title={<Space size={6}><HistoryOutlined /> 完整时间线（{release.timeline.length}）</Space>}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {[...release.timeline].reverse().map((t, i) => (
                <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'baseline', fontSize: 12.5 }}>
                  <Text type="secondary" style={{ flex: 'none', fontFamily: 'var(--mono)', fontSize: 11 }}>{fmtFull(t.at)}</Text>
                  <Text strong style={{ flex: 'none' }}>{t.action}</Text>
                  <Text type="secondary">{t.by}</Text>
                </div>
              ))}
            </div>
          </Section>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Section title={<Space size={6}><FileZipOutlined /> 产物</Space>}>
            {release.artifacts.length ? (
              release.artifacts.map((a) => (
                <div key={a.name} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0' }}>
                  <Text style={{ fontFamily: 'var(--mono)', fontSize: 12, flex: 1 }}>{a.name}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>{a.size}</Text>
                  <Button size="small" icon={<DownloadOutlined />} aria-label={`下载产物 ${a.name}`}
                    onClick={() => window.alert('原型演示：下载仅本地模拟')}>
                    下载
                  </Button>
                </div>
              ))
            ) : (
              <HintText>构建完成后生成产物。</HintText>
            )}
          </Section>

          {(release.status === 'failed' || release.notes.includes('风险')) && (
            <Section title={<Space size={6}><WarningOutlined style={{ color: 'var(--red)' }} /> 风险面板</Space>}>
              <HintText>{release.notes}</HintText>
              <Link to="/prototype/rollback">
                <Button size="small" danger icon={<RocketOutlined />} style={{ marginTop: 8 }}>
                  进入回滚向导
                </Button>
              </Link>
            </Section>
          )}

          <Section title="关联发布（同应用）">
            {related.length ? (
              related.map((r) => (
                <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0', fontSize: 12.5 }}>
                  <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
                  <Text style={{ flex: 1 }} ellipsis>{r.title}</Text>
                  <Text type="secondary" style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>v{r.version}</Text>
                  <ArrowRightOutlined style={{ color: 'var(--muted)', fontSize: 11 }} />
                </div>
              ))
            ) : (
              <HintText>暂无同应用的其他发布单。</HintText>
            )}
          </Section>
        </div>
      </div>
    </PageShell>
  );
}
