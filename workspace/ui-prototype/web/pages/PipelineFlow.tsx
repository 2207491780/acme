import { useMemo, useState } from 'react';
import { Progress, Segmented, Space, Tag, Typography } from 'antd';
import { CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons';
import {
  pipelineChain,
  platformShort,
  statusLabel,
  statusTone,
  TONE_ANTD,
  type PipelineStageView,
  type Status,
} from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

const STAGE_STYLE: Record<PipelineStageView['state'], { dot: string; label: string }> = {
  done: { dot: 'var(--acid)', label: 'var(--text-2)' },
  active: { dot: 'var(--accent)', label: 'var(--text)' },
  pending: { dot: 'var(--surface-3)', label: 'var(--muted)' },
  failed: { dot: 'var(--red)', label: 'var(--red)' },
};

const FILTERS: Array<{ value: 'all' | Status; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'building', label: '构建中' },
  { value: 'testing', label: '测试中' },
  { value: 'approved', label: '待发布' },
  { value: 'draft', label: '草稿' },
  { value: 'failed', label: '失败' },
];

/** V1 阶段管道图：每条发布单的 草稿→构建→测试→审批→发布 链路可视化，TICK 实时推进。 */
export default function PipelineFlow() {
  const state = useStore((s) => s.state);
  const [filter, setFilter] = useState<'all' | Status>('all');

  const releases = useMemo(
    () => state.releases.filter((r) => filter === 'all' || r.status === filter).slice(0, 10),
    [state.releases, filter],
  );

  return (
    <PageShell
      title="阶段管道图"
      domain="可视化看板 V1"
      desc="以链路形式呈现每条发布单所处阶段；构建节点实时显示 TICK 进度，失败节点标红。"
      extra={
        <Segmented value={filter} onChange={(v) => setFilter(v as 'all' | Status)} options={FILTERS} aria-label="按状态过滤管道" />
      }
    >
      {releases.length === 0 ? (
        <EmptyState title="没有匹配的发布单" desc="调整状态过滤条件。" />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {releases.map((r) => {
            const chain = pipelineChain(r);
            return (
              <Section
                key={r.id}
                title={
                  <Space wrap size={6}>
                    <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
                    <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{r.id}</Text>
                    <Text style={{ fontSize: 13 }}>{r.title}</Text>
                    <Tag>{platformShort(r.platform)}</Tag>
                  </Space>
                }
                extra={r.status === 'building' ? <Progress type="circle" size={28} percent={r.progress} strokeColor="var(--accent)" /> : undefined}
              >
                <div style={{ display: 'flex', alignItems: 'flex-start', overflowX: 'auto', paddingBottom: 4 }}>
                  {chain.map((s, i) => (
                    <div key={s.key} style={{ display: 'flex', alignItems: 'flex-start', flex: 1, minWidth: 96 }}>
                      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, minWidth: 72 }}>
                        {s.state === 'failed' ? (
                          <CloseCircleFilled style={{ fontSize: 20, color: STAGE_STYLE.failed.dot }} />
                        ) : s.state === 'done' ? (
                          <CheckCircleFilled style={{ fontSize: 20, color: STAGE_STYLE.done.dot }} />
                        ) : (
                          <span
                            aria-label={s.label}
                            style={{
                              width: 18,
                              height: 18,
                              borderRadius: '50%',
                              border: `2px solid ${STAGE_STYLE[s.state].dot}`,
                              background: s.state === 'active' ? STAGE_STYLE[s.state].dot : 'transparent',
                              boxShadow: s.state === 'active' ? '0 0 0 4px rgba(90,167,160,0.15)' : 'none',
                            }}
                          />
                        )}
                        <Text style={{ fontSize: 12, color: STAGE_STYLE[s.state].label }}>{s.label}</Text>
                        {s.note && <Text type="secondary" style={{ fontSize: 10 }}>{s.note}</Text>}
                      </div>
                      {i < chain.length - 1 && (
                        <div style={{ flex: 1, height: 2, background: s.state === 'done' ? 'var(--acid)' : 'var(--surface-3)', marginTop: 9, borderRadius: 2 }} />
                      )}
                    </div>
                  ))}
                </div>
              </Section>
            );
          })}
        </div>
      )}
      <HintText style={{ marginTop: 12 }}>
        链路口径与 A1 审批链一致（审批通过即 done）；成熟化时补预计剩余时间与超时预警。
      </HintText>
    </PageShell>
  );
}
