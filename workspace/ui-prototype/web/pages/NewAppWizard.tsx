import { useState } from 'react';
import { App, Button, Card, Descriptions, Form, Input, InputNumber, Result, Select, Steps, Tag, Typography } from 'antd';
import { AppstoreOutlined, CheckOutlined, GlobalOutlined, MobileOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { PLATFORMS, platformShort, platformLabel, type Platform } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

interface WizardForm {
  name: string;
  repoUrl: string;
  buildCommand: string;
  healthThreshold: number;
  owners: string[];
}

const { Text } = Typography;

const PLATFORM_ICON: Record<Platform, React.ReactNode> = {
  web: <GlobalOutlined />,
  desktop: <ThunderboltOutlined />,
  mobile: <MobileOutlined />,
};

/** E2 新建应用向导：平台 → 仓库与构建 → 确认创建（原型仅本地生效，不写 store）。 */
export default function NewAppWizard() {
  const { message } = App.useApp();
  const [step, setStep] = useState(0);
  const [platform, setPlatform] = useState<Platform | null>(null);
  const [form] = Form.useForm<WizardForm>();
  const [values, setValues] = useState<WizardForm | null>(null);
  const [done, setDone] = useState(false);

  const finishForm = async () => {
    const v = await form.validateFields();
    setValues(v);
    setStep(2);
  };

  return (
    <PageShell
      title="新建应用向导"
      domain="应用与配置域 E2"
      desc="三步创建应用接入：平台选择 → 仓库与构建配置 → 确认摘要；原型不写入正式数据。"
    >
      <Card size="small" style={{ marginBottom: 16 }}>
        <Steps size="small" current={done ? 3 : step} items={[{ title: '选择平台' }, { title: '仓库与构建' }, { title: '确认创建' }, { title: '完成' }]} />
      </Card>

      {done ? (
        <Card>
          <Result
            status="success"
            title="应用已创建（原型演示）"
            subTitle="正式版将写入应用清单并生成首批发布单模板。"
            extra={
              <Button onClick={() => { setDone(false); setStep(0); setPlatform(null); setValues(null); }}>
                再创建一个
              </Button>
            }
          />
        </Card>
      ) : (
        <>
          {step === 0 && (
            <Section title="① 选择目标平台">
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
                {PLATFORMS.map((p) => (
                  <Button
                    key={p}
                    block
                    size="large"
                    type={platform === p ? 'primary' : 'default'}
                    style={{ height: 96, display: 'flex', flexDirection: 'column', gap: 8, justifyContent: 'center' }}
                    onClick={() => setPlatform(p)}
                    aria-label={`选择平台 ${platformLabel(p)}`}
                  >
                    <span style={{ fontSize: 22 }}>{PLATFORM_ICON[p]}</span>
                    <span>{platformLabel(p)}</span>
                  </Button>
                ))}
              </div>
              <HintText style={{ marginTop: 12 }}>Web 为页面唯一来源；桌面端复用 Web 产物；移动端共享 core 业务层。</HintText>
            </Section>
          )}

          {step === 1 && (
            <Section title="② 仓库与构建配置">
              <Form form={form} layout="vertical" style={{ maxWidth: 560 }} requiredMark>
                <Form.Item name="name" label="应用名称" rules={[{ required: true, message: '请输入应用名称' }]}>
                  <Input placeholder="例如：Web 控制台" />
                </Form.Item>
                <Form.Item
                  name="repoUrl"
                  label="代码仓库地址"
                  rules={[
                    { required: true, message: '请输入仓库地址' },
                    { pattern: /^https?:\/\/.+/, message: '需为 http/https 地址' },
                  ]}
                >
                  <Input placeholder="https://git.example.com/acme/web.git" />
                </Form.Item>
                <Form.Item name="buildCommand" label="构建命令" rules={[{ required: true, message: '请输入构建命令' }]}>
                  <Input placeholder="pnpm build" />
                </Form.Item>
                <Form.Item name="healthThreshold" label="错误率告警阈值（%）" initialValue={0.5} rules={[{ required: true }]}>
                  <InputNumber min={0.1} max={5} step={0.1} style={{ width: 160 }} />
                </Form.Item>
                <Form.Item name="owners" label="初始负责人（可多选）" rules={[{ required: true, message: '至少选择一人' }]}>
                  <Select mode="multiple" placeholder="选择成员" options={['林晚', '陈屿', '苏然', '周衍', '何青'].map((n) => ({ value: n, label: n }))} />
                </Form.Item>
              </Form>
            </Section>
          )}

          {step === 2 && platform && values && (
            <Section title="③ 确认创建摘要">
              <Descriptions bordered size="small" column={1}
                items={[
                  { key: 'p', label: '平台', children: <Tag icon={PLATFORM_ICON[platform]}>{platformShort(platform)}</Tag> },
                  { key: 'n', label: '应用名称', children: values.name },
                  { key: 'r', label: '仓库', children: <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{values.repoUrl}</Text> },
                  { key: 'b', label: '构建命令', children: <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{values.buildCommand}</Text> },
                  { key: 't', label: '告警阈值', children: `错误率 > ${values.healthThreshold}%` },
                  { key: 'o', label: '负责人', children: values.owners.join('、') },
                ]}
              />
              <HintText style={{ marginTop: 12 }}>
                创建后可在正式「应用结构」页维护健康度与负责人（当前原型不写入）。
              </HintText>
            </Section>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 16 }}>
            <Button onClick={() => (step === 0 ? undefined : setStep(step - 1))} disabled={step === 0}>
              上一步
            </Button>
            {step === 0 ? (
              <Button type="primary" icon={<AppstoreOutlined />} disabled={!platform} onClick={() => setStep(1)}>
                下一步
              </Button>
            ) : step === 1 ? (
              <Button type="primary" icon={<CheckOutlined />} onClick={finishForm}>
                下一步
              </Button>
            ) : (
              <Button
                type="primary"
                onClick={() => {
                  setDone(true);
                  message.success('原型演示：应用创建仅本地生效');
                }}
              >
                创建应用
              </Button>
            )}
          </div>
        </>
      )}
    </PageShell>
  );
}
