import { useMemo } from 'react';
import { App, Button, Progress, Space, Tag, Typography } from 'antd';
import { PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { grayPlans, type GrayPlan } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** S2 灰度发布管理：5% → 50% → 100% 三阶段放量计划与各批次成功率（确定性演示）。 */
export default function GrayRelease() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const plans = useMemo(() => grayPlans(state, Date.now()), [state]);

  return (
    <PageShell
      title="灰度发布管理"
      domain="稳定性域 S2"
      desc="按批次的放量控制：5% → 50% → 100%，各批次成功率与反馈；异常可一键暂停。"
    >
      {plans.length === 0 ? (
        <EmptyState title="暂无灰度计划" desc="已发布的版本会自动生成灰度计划（演示口径）。" />
      ) : (
        plans.map((plan: GrayPlan) => {
          const current = plan.stages.find((s) => s.state === 'active');
          const doneStages = plan.stages.filter((s) => s.state === 'done').length;
          return (
            <Section
              key={plan.id}
              title={
                <Space size={8} wrap>
                  <Text strong>{plan.appName}</Text>
                  <Tag color="processing">v{plan.version}</Tag>
                  {plan.paused && <Tag color="warning">已暂停</Tag>}
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    阶段 {doneStages}/{plan.stages.length} · 当前 {current?.percent ?? 100}%
                  </Text>
                </Space>
              }
              extra={
                <Button
                  size="small"
                  icon={plan.paused ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
                  onClick={() => message.info(plan.paused ? '原型演示：恢复放量' : '原型演示：暂停放量（仅本地生效）')}
                >
                  {plan.paused ? '恢复' : '暂停'}
                </Button>
              }
              style={{ marginBottom: 16 }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
                <Progress
                  percent={current?.percent ?? 100}
                  strokeColor={plan.paused ? 'var(--amber)' : 'var(--accent)'}
                  status={plan.paused ? 'exception' : 'active'}
                  style={{ flex: 1, marginBottom: 0 }}
                />
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {plan.paused ? '已暂停放量' : `放量至 ${current?.percent ?? 100}%`}
                </Text>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
                {plan.stages.map((s) => (
                  <div key={s.percent} style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 12, background: s.state === 'active' ? 'var(--surface-2)' : 'transparent' }}>
                    <Space size={6} wrap style={{ marginBottom: 6 }}>
                      <Tag color={s.state === 'done' ? 'success' : s.state === 'active' ? 'processing' : 'default'}>
                        {s.state === 'done' ? '已完成' : s.state === 'active' ? '进行中' : '待放量'} {s.percent}%
                      </Tag>
                      {s.state !== 'pending' && <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>成功率 {s.successRate}%</Text>}
                    </Space>
                    <div style={{ fontSize: 12, color: 'var(--text-2)' }}>{s.feedback}</div>
                  </div>
                ))}
              </div>
            </Section>
          );
        })
      )}
      <HintText>
        成熟化方向：按用户分群放量、自动回滚阈值（错误率超阈值即暂停并提示 A5）、放量窗口校验（联动 H1 冻结期）。
      </HintText>
    </PageShell>
  );
}
