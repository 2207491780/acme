import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Card, Progress, Space, Tag, Typography } from 'antd';
import { CheckCircleFilled, ClockCircleOutlined } from '@ant-design/icons';
import { onboardingAutoState, ONBOARDING_STEPS } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

/** X3 新手引导清单：自动判定（真实 store 推导）+ 手动勾选混合；完成度即首次上手进度。 */
export default function Onboarding() {
  const navigate = useNavigate();
  const state = useStore((s) => s.state);
  const [manual, setManual] = useState<string[]>([]);

  const steps = ONBOARDING_STEPS.map((step) => {
    const auto = onboardingAutoState(state, step);
    const done = auto !== null ? auto : manual.includes(step.key);
    return { ...step, done };
  });
  const doneCount = steps.filter((s) => s.done).length;

  return (
    <PageShell
      title="新手引导清单"
      domain="横向专项 X3"
      desc="首次上手六步走：三项由真实数据自动判定（已发布/已审批/有产物），三项手动确认演练完成。"
    >
      <Card size="small" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Progress
            type="circle"
            size={64}
            percent={Math.round((doneCount / steps.length) * 100)}
            strokeColor={doneCount === steps.length ? 'var(--acid)' : 'var(--accent)'}
          />
          <div>
            <Text strong style={{ fontSize: 15 }}>
              {doneCount === steps.length ? '🎉 上手完成，尽情探索其余原型！' : `已完成 ${doneCount}/${steps.length}`}
            </Text>
            <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 4 }}>
              自动判定项无需手动勾选——完成对应动作（真实写入 store）后自动点亮。
            </div>
          </div>
        </div>
      </Card>

      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        {steps.map((step, i) => (
          <Card key={step.key} size="small" style={step.done ? { borderColor: 'var(--acid)' } : undefined}>
            <Space size={10} align="start">
              {step.done ? (
                <CheckCircleFilled style={{ color: 'var(--acid)', fontSize: 18, marginTop: 2 }} />
              ) : (
                <ClockCircleOutlined style={{ color: 'var(--surface-3)', fontSize: 18, marginTop: 2 }} />
              )}
              <div style={{ flex: 1 }}>
                <Space size={6} wrap>
                  <Text strong style={{ fontSize: 13.5 }}>
                    {i + 1}. {step.label}
                  </Text>
                  {step.auto ? <Tag color="processing">自动判定</Tag> : <Tag>手动确认</Tag>}
                  {step.done && <Tag color="success">已完成</Tag>}
                </Space>
                <div style={{ fontSize: 12.5, color: 'var(--text-2)', marginTop: 4 }}>{step.desc}</div>
                <Space size={6} style={{ marginTop: 8 }}>
                  <Button size="small" onClick={() => navigate(step.target)}>前往</Button>
                  {!step.auto && (
                    <Button
                      size="small"
                      type={step.done ? 'default' : 'primary'}
                      onClick={() => setManual((cur) => (cur.includes(step.key) ? cur.filter((k) => k !== step.key) : [...cur, step.key]))}
                    >
                      {step.done ? '取消勾选' : '标记完成'}
                    </Button>
                  )}
                </Space>
              </div>
            </Space>
          </Card>
        ))}
      </Space>

      <Section title="说明" style={{ marginTop: 16 }}>
        <HintText>
          清单即上手路径：创建 → 流转 → 审批 → 产物 → 发布 → 回滚演练，覆盖发布全流程核心页面。
          成熟化时可用 localStorage 持久化勾选状态并按角色裁剪。
        </HintText>
        <Link to="/prototype" style={{ fontSize: 12 }}>← 返回原型索引</Link>
      </Section>
    </PageShell>
  );
}
