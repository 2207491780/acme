import { useState } from 'react';
import { App, Button, Card, InputNumber, Select, Space, Switch, Tag, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { ENV_ANTD, envLabel, FLOW_RULES, MEMBERS, type FlowRule } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { HintText, Row, Section } from '../components/blocks';

const { Text } = Typography;

/** B1 审批流配置：按环境配置审批人数 / 审批人 / 自动流转（原型仅本地生效）。 */
export default function ApprovalFlowConfig() {
  const { message } = App.useApp();
  const [rules, setRules] = useState<FlowRule[]>(FLOW_RULES);
  const [dirty, setDirty] = useState(false);

  const patch = (id: string, p: Partial<FlowRule>) => {
    setRules((cur) => cur.map((r) => (r.id === id ? { ...r, ...p } : r)));
    setDirty(true);
  };

  return (
    <PageShell
      title="审批流配置"
      domain="流程配置域 B1"
      desc="按环境定义审批人数、审批人与自动流转策略；成熟化后作为正式审批链（A1）的规则来源。"
      extra={
        <Button
          type="primary"
          icon={<SaveOutlined />}
          disabled={!dirty}
          onClick={() => {
            setDirty(false);
            message.success('原型演示：配置仅本地生效');
          }}
        >
          保存配置
        </Button>
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
        {rules.map((rule) => (
          <Card
            key={rule.id}
            size="small"
            title={
              <Space size={6}>
                <Tag color={ENV_ANTD[rule.env]}>{envLabel(rule.env)}</Tag>
                <Text strong>审批规则</Text>
              </Space>
            }
          >
            <Row label="最少审批人">
              <InputNumber
                min={0}
                max={5}
                value={rule.minApprovers}
                onChange={(v) => patch(rule.id, { minApprovers: v ?? 0 })}
                style={{ width: 80 }}
                aria-label={`${rule.env} 最少审批人`}
              />
              <Text type="secondary" style={{ fontSize: 12 }}>0 = 免审自动通过</Text>
            </Row>
            <Row label="审批人池">
              <Select
                mode="multiple"
                style={{ minWidth: 180 }}
                value={rule.approvers}
                onChange={(v) => patch(rule.id, { approvers: v })}
                placeholder="选择成员"
                aria-label={`${rule.env} 审批人池`}
                options={MEMBERS.map((m) => ({ value: m.name, label: `${m.name}（${m.role === 'owner' ? '负责人' : m.role}）` }))}
              />
            </Row>
            <Row label="通过后自动发布">
              <Switch
                checked={rule.autoOnPass}
                onChange={(v) => patch(rule.id, { autoOnPass: v })}
                aria-label={`${rule.env} 自动发布`}
              />
              <Text type="secondary" style={{ fontSize: 12 }}>{rule.autoOnPass ? '免人工触发' : '需人工点击发布'}</Text>
            </Row>
          </Card>
        ))}
      </div>

      <Section title="预览：规则如何作用于审批链（A1）" style={{ marginTop: 16 }}>
        {rules.map((r) => (
          <div key={r.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0', fontSize: 12.5 }}>
            <Tag color={ENV_ANTD[r.env]}>{envLabel(r.env)}</Tag>
            <span>
              {r.minApprovers === 0
                ? '免审：构建通过即自动进入发布确认'
                : `需 ${r.minApprovers} 人（${r.approvers.join('、') || '未配置'}）依次审批`}
              ；{r.autoOnPass ? '通过后自动发布' : '通过后等待人工发布'}
            </span>
          </div>
        ))}
        <HintText style={{ marginTop: 10 }}>
          成熟化方向：条件分支（如按版本号/风险等级选路）、代理审批、审批超时升级。
        </HintText>
      </Section>
    </PageShell>
  );
}
