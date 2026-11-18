import { useState } from 'react';
import { App, Button, Card, Descriptions, Form, Input, InputNumber, Result, Select, Space, Steps, Typography } from 'antd';
import { CheckOutlined, PlusOutlined } from '@ant-design/icons';
import { ENVS, envLabel, platformShort } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

interface WizardValues {
  title: string;
  appId: string;
  version: string;
  env: (typeof ENVS)[number];
  owner: string;
  priority: number;
  notes: string;
}

const STEPS = ['目标应用', '发布信息', '发布前确认', '完成'];

/** R2 新建发布单向导：正式页的新建为模态框；本原型验证多步向导形态（信息分组更从容）。 */
export default function CreateWizard() {
  const { message } = App.useApp();
  const apps = useStore((s) => s.state.apps);
  const releaseCount = useStore((s) => s.state.releases.length);
  const [step, setStep] = useState(0);
  const [appId, setAppId] = useState(apps[0]?.id ?? '');
  const [values, setValues] = useState<WizardValues | null>(null);
  const [done, setDone] = useState(false);
  const [form] = Form.useForm<WizardValues>();

  const selectedApp = apps.find((a) => a.id === appId);

  return (
    <PageShell
      title="新建发布单向导"
      domain="发布工程域 R2"
      desc="四步创建：选目标应用 → 填发布信息 → 发布前确认（链接 R1 检查口径）→ 完成；原型不写入正式数据。"
    >
      <Card size="small" style={{ marginBottom: 16 }}>
        <Steps size="small" current={done ? 3 : Math.min(step, 3)} items={STEPS.map((t) => ({ title: t }))} />
      </Card>

      {done ? (
        <Card>
          <Result
            status="success"
            title="发布单已创建（原型演示）"
            subTitle={`${values?.appId} · ${values?.version} → ${values?.env}；正式版将写入 store 并进入 A1 审批链。`}
            extra={<Button onClick={() => { setDone(false); setStep(0); setValues(null); form.resetFields(); }}>再创建一个</Button>}
          />
        </Card>
      ) : (
        <>
          {step === 0 && (
            <Section title="① 选择目标应用">
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
                {apps.map((a) => (
                  <Button
                    key={a.id}
                    block
                    type={appId === a.id ? 'primary' : 'default'}
                    style={{ textAlign: 'left', height: 'auto', padding: '10px 14px', justifyContent: 'flex-start' }}
                    onClick={() => setAppId(a.id)}
                    aria-label={`选择应用 ${a.name}`}
                  >
                    <div>
                      <Text strong style={{ color: appId === a.id ? '#000' : undefined }}>{a.name}</Text>
                      <div style={{ fontSize: 11, opacity: 0.75 }}>{platformShort(a.platform)} · v{a.version} · {a.owner}</div>
                    </div>
                  </Button>
                ))}
              </div>
              <HintText style={{ marginTop: 10 }}>平台由应用决定（发布单自动继承）；桌面端将自动关联 Web 产物依赖。</HintText>
            </Section>
          )}

          {step === 1 && (
            <Section title="② 发布信息">
              <Form form={form} layout="vertical" style={{ maxWidth: 560 }} requiredMark initialValues={{ env: 'dev', priority: releaseCount + 1 }}>
                <Form.Item name="title" label="发布标题" rules={[{ required: true, message: '请输入标题' }, { max: 60 }]}>
                  <Input placeholder="例如：登录页视觉升级" maxLength={60} />
                </Form.Item>
                <Space size={16} wrap>
                  <Form.Item name="version" label="版本号" rules={[{ required: true, pattern: /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/, message: '如 1.2.3-beta.1' }]}>
                    <Input placeholder="1.2.3" style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name="env" label="目标环境" rules={[{ required: true }]}>
                    <Select style={{ width: 140 }} options={ENVS.map((e) => ({ value: e, label: envLabel(e) }))} />
                  </Form.Item>
                  <Form.Item name="priority" label="优先级" rules={[{ required: true }]}>
                    <InputNumber min={1} max={999} style={{ width: 120 }} />
                  </Form.Item>
                </Space>
                <Form.Item name="owner" label="负责人" rules={[{ required: true }]}>
                  <Input placeholder="姓名" style={{ width: 200 }} />
                </Form.Item>
                <Form.Item name="notes" label="发布说明 / 风险 / 回滚预案">
                  <Input.TextArea rows={3} placeholder="按多端交付流程执行…" />
                </Form.Item>
              </Form>
              <HintText>选择应用：{selectedApp?.name ?? '—'}（{selectedApp ? platformShort(selectedApp.platform) : ''}）</HintText>
            </Section>
          )}

          {step === 2 && values && (
            <Section title="③ 发布前确认">
              <Descriptions bordered size="small" column={1}
                items={[
                  { key: 'app', label: '目标应用', children: selectedApp?.name ?? values.appId },
                  { key: 't', label: '标题', children: values.title },
                  { key: 'v', label: '版本 / 环境', children: `v${values.version} → ${envLabel(values.env)}` },
                  { key: 'o', label: '负责人 / 优先级', children: `${values.owner} · P${values.priority}` },
                  { key: 'n', label: '说明', children: values.notes || '—' },
                ]}
              />
              <HintText style={{ marginTop: 10 }}>
                提交后将按 R1 就绪度检查逐项核验（构建 → 测试 → 审批 → 产物 → 窗口 → 回滚预案）。
              </HintText>
            </Section>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 16 }}>
            <Button disabled={step === 0} onClick={async () => {
              if (step === 2) { setStep(1); return; }
              setStep(step - 1);
            }}>上一步</Button>
            {step === 0 ? (
              <Button type="primary" icon={<PlusOutlined />} disabled={!appId} onClick={() => setStep(1)}>下一步</Button>
            ) : step === 1 ? (
              <Button
                type="primary"
                icon={<CheckOutlined />}
                onClick={async () => {
                  const v = await form.validateFields();
                  setValues(v);
                  setStep(2);
                }}
              >
                下一步
              </Button>
            ) : (
              <Button
                type="primary"
                onClick={() => { setDone(true); message.success('原型演示：创建仅本地生效'); }}
              >
                创建发布单
              </Button>
            )}
          </div>
        </>
      )}
    </PageShell>
  );
}
