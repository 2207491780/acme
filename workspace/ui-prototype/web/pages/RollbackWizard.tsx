import { useState } from 'react';
import { App, Alert, Button, Card, Checkbox, Radio, Space, Steps, Tag, Typography } from 'antd';
import { CheckCircleOutlined, RocketOutlined } from '@ant-design/icons';
import { fmtFull, RISK_LABEL, rollbackOptions, type RollbackOption } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

const RISK_COLOR: Record<RollbackOption['risk'], string> = { low: 'success', medium: 'warning', high: 'error' };

const CHECKLIST = [
  { key: 'freeze', label: '已冻结当前发布窗口（暂停进行中的流水线）' },
  { key: 'backup', label: '已确认当前版本产物与数据快照可追溯' },
  { key: 'review', label: '高风险操作已获得双人复核' },
];

/** A5 回滚向导：选发布 → 选版本 → 安全确认 → 执行（原型仅本地生效）。 */
export default function RollbackWizard() {
  const { message } = App.useApp();
  const releases = useStore((s) => s.state.releases);
  const [step, setStep] = useState(0);
  const [releaseId, setReleaseId] = useState<string | null>(null);
  const [option, setOption] = useState<RollbackOption | null>(null);
  const [checked, setChecked] = useState<string[]>([]);
  const [done, setDone] = useState(false);

  const release = releases.find((r) => r.id === releaseId);
  const options = release ? rollbackOptions({ releases }, release, Date.now()) : [];

  const reset = () => {
    setStep(0);
    setReleaseId(null);
    setOption(null);
    setChecked([]);
    setDone(false);
  };

  const canNext =
    (step === 0 && !!releaseId) ||
    (step === 1 && !!option) ||
    (step === 2 && CHECKLIST.every((c) => checked.includes(c.key)));

  return (
    <PageShell
      title="回滚向导"
      domain="发布单域 A5"
      desc="面向失败/事故场景的多步回滚：版本选择 → 影响面确认 → 安全检查 → 执行；全程提示风险等级。"
    >
      <Card size="small" style={{ marginBottom: 16 }}>
        <Steps
          size="small"
          current={done ? 3 : step}
          items={[{ title: '选择发布单' }, { title: '选择回滚版本' }, { title: '安全确认' }, { title: '完成' }]}
        />
      </Card>

      {done ? (
        <Card>
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <CheckCircleOutlined style={{ fontSize: 42, color: 'var(--acid)' }} />
            <h3 style={{ margin: '12px 0 4px' }}>回滚已完成（原型演示）</h3>
            <Text type="secondary">
              {release?.appId} 已从 v{release?.version} 回滚至 v{option?.version}；正式版将联动动态流与审计。
            </Text>
            <div style={{ marginTop: 16 }}>
              <Button onClick={reset}>再来一次</Button>
            </div>
          </div>
        </Card>
      ) : (
        <>
          {step === 0 && (
            <Section title="① 选择要回滚的发布单">
              <Space direction="vertical" style={{ width: '100%' }}>
                {releases
                  .filter((r) => ['published', 'failed', 'approved'].includes(r.status))
                  .map((r) => (
                    <Button
                      key={r.id}
                      block
                      style={{ textAlign: 'left', justifyContent: 'flex-start' }}
                      type={releaseId === r.id ? 'primary' : 'default'}
                      onClick={() => setReleaseId(r.id)}
                      aria-label={`选择发布单 ${r.id}`}
                    >
                      <Space wrap>
                        <Tag color={r.status === 'failed' ? 'error' : 'success'}>{r.id}</Tag>
                        {r.title} · v{r.version} · {r.env}
                      </Space>
                    </Button>
                  ))}
              </Space>
            </Section>
          )}

          {step === 1 && release && (
            <Section title="② 选择回滚目标版本">
              {options.length === 0 ? (
                <EmptyState title="没有可回滚的历史版本" desc="该应用在此环境暂无已发布记录。" />
              ) : (
                <Radio.Group
                  style={{ width: '100%' }}
                  value={option?.version}
                  onChange={(e) => setOption(options.find((o) => o.version === e.target.value) ?? null)}
                  aria-label="选择回滚目标版本"
                >
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {options.map((o) => (
                      <Radio key={o.version} value={o.version} style={{ width: '100%' }}>
                        <Space wrap>
                          <Tag color={RISK_COLOR[o.risk]}>{RISK_LABEL[o.risk]}</Tag>
                          <Text strong>v{o.version}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>发布于 {fmtFull(o.releasedAt)}</Text>
                        </Space>
                        <div style={{ fontSize: 12, color: 'var(--text-2)', paddingLeft: 2 }}>{o.note}</div>
                      </Radio>
                    ))}
                  </Space>
                </Radio.Group>
              )}
            </Section>
          )}

          {step === 2 && release && option && (
            <Section title="③ 安全确认">
              {option.risk === 'high' && (
                <Alert type="error" showIcon style={{ marginBottom: 12 }} message="高风险回滚" description={option.note} />
              )}
              <Space direction="vertical" style={{ width: '100%' }}>
                {CHECKLIST.map((c) => (
                  <Checkbox
                    key={c.key}
                    checked={checked.includes(c.key)}
                    onChange={(e) =>
                      setChecked((cur) => (e.target.checked ? [...cur, c.key] : cur.filter((x) => x !== c.key)))
                    }
                    aria-label={c.label}
                  >
                    {c.label}
                  </Checkbox>
                ))}
              </Space>
              <HintText style={{ marginTop: 12 }}>
                将执行：{release.appId} v{release.version} → v{option.version}（{release.env}）。
              </HintText>
            </Section>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 16 }}>
            <Button onClick={() => (step === 0 ? reset() : setStep(step - 1))}>
              {step === 0 ? '重置' : '上一步'}
            </Button>
            {step < 2 ? (
              <Button type="primary" disabled={!canNext} onClick={() => setStep(step + 1)}>
                下一步
              </Button>
            ) : (
              <Button
                type="primary"
                danger
                icon={<RocketOutlined />}
                disabled={!canNext}
                onClick={() => {
                  setDone(true);
                  message.warning('原型演示：回滚仅本地生效，未执行任何真实操作');
                }}
              >
                执行回滚
              </Button>
            )}
          </div>
        </>
      )}
    </PageShell>
  );
}
