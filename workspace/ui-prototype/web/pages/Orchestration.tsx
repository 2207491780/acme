import { useMemo } from 'react';
import { Alert, Space, Tag, Typography } from 'antd';
import { ApartmentOutlined } from '@ant-design/icons';
import {
  orchestrationGraph,
  platformShort,
  statusLabel,
  statusTone,
  TONE_ANTD,
  topoSort,
  type OrchestrationNode,
} from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** A6 批量编排：按依赖拓扑分批的执行批次视图（mobile → web → desktop），含成环检测。 */
export default function Orchestration() {
  const state = useStore((s) => s.state);
  const nodes = useMemo(() => orchestrationGraph(state), [state]);
  const { order, cycles } = useMemo(() => topoSort(nodes), [nodes]);

  if (!nodes.length) {
    return (
      <PageShell title="批量编排" domain="发布单域 A6" desc="多应用发布顺序编排与可行性校验。">
        <EmptyState title="没有可编排的发布单" desc="草稿 / 构建中 / 测试中 / 待发布的单会自动进入编排。" />
      </PageShell>
    );
  }

  // 按拓扑层级分批：第 i 批 = 依赖已全部在前序批次的节点
  const batches: OrchestrationNode[][] = [];
  const placed = new Set<string>();
  const byId = new Map(nodes.map((n) => [n.releaseId, n]));
  for (const id of order) {
    const node = byId.get(id)!;
    const batch = batches.findIndex(() => node.dependsOn.every((d) => placed.has(d)));
    if (batch >= 0) batches[batch].push(node);
    else batches.push([node]);
    placed.add(id);
  }

  return (
    <PageShell
      title="批量编排"
      domain="发布单域 A6"
      desc="多应用发布顺序依赖编排（演示口径：mobile → web → desktop 桥接），按拓扑批次执行；成环时阻断并标红。"
    >
      {cycles.length > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={`存在循环依赖，无法编排（${cycles.length} 个节点）`}
          description={cycles.map((id) => byId.get(id)?.title).join('、')}
        />
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {batches.map((batch, i) => (
          <Section
            key={i}
            title={
              <Space size={8}>
                <Tag color="processing">执行批次 {i + 1}</Tag>
                <span style={{ fontSize: 12, color: 'var(--muted)' }}>{batch.length} 个发布单，依赖已就绪</span>
              </Space>
            }
          >
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 10 }}>
              {batch.map((n) => (
                <div key={n.releaseId} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 12, background: 'var(--surface-2)' }}>
                  <Space wrap size={6} style={{ marginBottom: 6 }}>
                    <Tag color={TONE_ANTD[statusTone(n.status)]}>{statusLabel(n.status)}</Tag>
                    <Tag>{platformShort(state.apps.find((a) => a.id === n.appId)?.platform ?? 'web')}</Tag>
                    <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{n.releaseId}</Text>
                  </Space>
                  <div style={{ fontSize: 13, marginBottom: 6 }}>{n.title}</div>
                  {n.dependsOn.length > 0 ? (
                    <div style={{ fontSize: 11, color: 'var(--muted)' }}>
                      <ApartmentOutlined /> 依赖：{n.dependsOn.map((d) => byId.get(d)?.title ?? d).join('、')}
                    </div>
                  ) : (
                    <div style={{ fontSize: 11, color: 'var(--muted)' }}>无前置依赖，可最先执行</div>
                  )}
                </div>
              ))}
            </div>
          </Section>
        ))}
      </div>
      <HintText style={{ marginTop: 12 }}>
        成熟化方向：拖拽调整依赖、依赖缺失预警、按批次一键推进（复用正式 bulkAdvance 口径）。
      </HintText>
    </PageShell>
  );
}
