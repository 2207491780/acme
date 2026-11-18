import { useMemo, useState } from 'react';
import { App, Button, Segmented, Space, Tag, Typography } from 'antd';
import { AlertOutlined, RocketOutlined } from '@ant-design/icons';
import { fmtFull, incidents, platformShort, type Incident } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

const SEVERITY_COLOR: Record<Incident['severity'], string> = { P1: 'error', P2: 'warning', P3: 'default' };

/** S1 事故与事件流：失败发布单 → 事故单（严重度/MTTR/回滚就绪），MTTR 统计与筛选。 */
export default function IncidentStream() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const data = useMemo(() => incidents(state, Date.now()), [state]);
  const [filter, setFilter] = useState<'all' | 'open' | 'resolved' | 'P1'>('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const filtered = data.list.filter((x) =>
    filter === 'all' ? true : filter === 'open' ? !x.resolved : filter === 'resolved' ? x.resolved : x.severity === 'P1',
  );
  const selected: Incident | undefined = data.list.find((x) => x.id === selectedId);

  return (
    <PageShell
      title="事故与事件流"
      domain="稳定性域 S1"
      desc="失败发布单自动转为事故单：严重度、MTTR、回滚就绪度；成熟化时接入真实告警源（联动 G1 监控集成）。"
    >
      <KpiRow>
        <Kpi label="事故总数" value={data.list.length} tone="danger" />
        <Kpi label="未关闭" value={data.open} tone="warning" hint={data.open ? '需要跟进' : undefined} />
        <Kpi label="平均 MTTR" value={data.mttrAvgHours} unit="小时" tone="info" />
        <Kpi label="回滚就绪" value={`${data.list.filter((x) => x.rollbackReady).length}/${data.list.length}`} tone="success" />
      </KpiRow>

      <Section
        title={
          <Space size={8} wrap>
            <Segmented
              size="small"
              value={filter}
              onChange={(v) => setFilter(v as typeof filter)}
              options={[
                { value: 'all', label: '全部' },
                { value: 'open', label: '未关闭' },
                { value: 'resolved', label: '已关闭' },
                { value: 'P1', label: 'P1' },
              ]}
              aria-label="事故筛选"
            />
            <Tag>{filtered.length} 条</Tag>
          </Space>
        }
      >
        {filtered.length === 0 ? (
          <EmptyState title="没有匹配的事故" desc="当前筛选条件下暂无事故单。" />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            {filtered.map((x) => (
              <div
                key={x.id}
                onClick={() => setSelectedId(x.id === selectedId ? null : x.id)}
                style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 8px', borderBottom: '1px solid var(--border)', cursor: 'pointer', borderRadius: 8, background: selectedId === x.id ? 'var(--surface-2)' : 'transparent', flexWrap: 'wrap' }}
              >
                <AlertOutlined style={{ color: x.resolved ? 'var(--acid)' : 'var(--red)', fontSize: 15 }} />
                <Tag color={SEVERITY_COLOR[x.severity]}>{x.severity}</Tag>
                <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{x.id}</Text>
                <Text style={{ fontSize: 13, flex: 1, minWidth: 160 }} ellipsis>{x.title}</Text>
                <Text type="secondary" style={{ fontSize: 11, fontFamily: 'var(--mono)' }}>{x.releaseId} · {platformShort(x.appId === 'web' ? 'web' : x.appId === 'desktop' ? 'desktop' : 'mobile')}</Text>
                <Tag>{x.env}</Tag>
                <Tag color={x.resolved ? 'success' : 'processing'}>{x.resolved ? `已关闭 · ${x.mttrHours}h` : '处理中'}</Tag>
                {x.rollbackReady && <Tag color="success">回滚就绪</Tag>}
              </div>
            ))}
          </div>
        )}
      </Section>

      {selected && (
        <Section
          title={`事故详情 · ${selected.id}`}
          style={{ marginTop: 16 }}
          extra={
            <Button
              size="small"
              icon={<RocketOutlined />}
              onClick={() => message.info('原型演示：跳转回滚向导（A5）仅本地生效')}
            >
              前往回滚向导
            </Button>
          }
        >
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10, fontSize: 12.5 }}>
            <span>发布单：<Text strong style={{ fontFamily: 'var(--mono)' }}>{selected.releaseId}</Text></span>
            <span>环境：<Tag>{selected.env}</Tag></span>
            <span>发生时间：<Text type="secondary">{fmtFull(selected.openedAt)}</Text></span>
            <span>状态：<Tag color={selected.resolved ? 'success' : 'processing'}>{selected.resolved ? `已关闭（${selected.mttrHours}h）` : '处理中'}</Tag></span>
          </div>
          <HintText style={{ marginTop: 10 }}>
            处置链路：事故确认 → 失败归因（C1）→ 回滚执行（A5）→ 复盘留痕（C3 审计）。
          </HintText>
        </Section>
      )}
    </PageShell>
  );
}
