import { useMemo, useState } from 'react';
import { App, Button, Card, Col, Row, Space, Switch, Tag, Typography } from 'antd';
import { ExperimentOutlined, HistoryOutlined } from '@ant-design/icons';
import { ENV_ANTD, FLAG_SCOPE_LABEL, FEATURE_FLAGS, type FeatureFlag } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

/** W2 功能开关管理：开关清单、影响面标注、变更留痕提示（原型仅本地生效）。 */
export default function FeatureFlags() {
  const { message } = App.useApp();
  const [flags, setFlags] = useState<FeatureFlag[]>(FEATURE_FLAGS);
  const [dirtyKeys, setDirtyKeys] = useState<Set<string>>(new Set());

  const toggle = (key: string, v: boolean) => {
    setFlags((cur) => cur.map((f) => (f.key === key ? { ...f, enabled: v } : f)));
    setDirtyKeys((cur) => new Set(cur).add(key));
  };

  const saveAll = () => {
    setDirtyKeys(new Set());
    message.success(`原型演示：已保存 ${dirtyKeys.size} 项变更并写入审计（联动 C3 口径）`);
  };

  const enabledCount = useMemo(() => flags.filter((f) => f.enabled).length, [flags]);

  return (
    <PageShell
      title="功能开关管理"
      domain="内容与治理域 W2"
      desc="运行时行为开关：影响面、责任人与作用域一目了然；变更需留痕（审计口径）。"
      extra={
        <Button
          type="primary"
          icon={<HistoryOutlined />}
          disabled={dirtyKeys.size === 0}
          onClick={saveAll}
          aria-label="保存开关变更"
        >
          保存变更（{dirtyKeys.size}）
        </Button>
      }
    >
      <KpiRow>
        <Kpi label="开关总数" value={flags.length} tone="info" />
        <Kpi label="启用中" value={enabledCount} tone="success" />
        <Kpi label="已禁用" value={flags.length - enabledCount} tone="muted" />
        <Kpi label="未保存变更" value={dirtyKeys.size} tone={dirtyKeys.size ? 'warning' : 'muted'} />
      </KpiRow>

      <Row gutter={[14, 14]}>
        {flags.map((flag) => (
          <Col xs={24} md={12} key={flag.key}>
            <Card
              size="small"
              style={{ height: '100%', borderColor: dirtyKeys.has(flag.key) ? 'var(--amber)' : 'var(--border)' }}
              title={
                <Space size={6} wrap>
                  <ExperimentOutlined style={{ color: 'var(--accent)' }} />
                  <Text strong>{flag.name}</Text>
                  <Tag color={ENV_ANTD[flag.scope === 'all' ? 'dev' : flag.scope]}>{
                    FLAG_SCOPE_LABEL[flag.scope]
                  }</Tag>
                  {dirtyKeys.has(flag.key) && <Tag color="warning">未保存</Tag>}
                </Space>
              }
              extra={
                <Switch
                  checked={flag.enabled}
                  onChange={(v) => toggle(flag.key, v)}
                  checkedChildren="开"
                  unCheckedChildren="关"
                  aria-label={`切换 ${flag.name}`}
                />
              }
            >
              <div style={{ fontSize: 12.5, color: 'var(--text-2)', marginBottom: 8, lineHeight: 1.7 }}>{flag.description}</div>
              <Space size={6} wrap style={{ fontSize: 11 }}>
                <Text type="secondary">责任人：{flag.owner}</Text>
                <Tag color={flag.enabled ? 'success' : 'default'}>影响面：{flag.impact}</Tag>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Section title="口径与联动" style={{ marginTop: 16 }}>
        <HintText>
          「灰度自动暂停」作用于 S2；「prod 双人审批」作用于 B1/A1（关闭违反发布纪律，卡片提示不建议）；
          「排期冲突检测」作用于 A4。成熟化时开关变更写入 C3 审计并支持按环境快照回滚。
        </HintText>
      </Section>
    </PageShell>
  );
}
