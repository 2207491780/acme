import { useState } from 'react';
import { App, Button, Card, Col, Row, Space, Tag, Typography } from 'antd';
import { ApiOutlined, CodeOutlined, MailOutlined, MonitorOutlined, RocketOutlined, ToolOutlined } from '@ant-design/icons';
import { fmtDate, INTEGRATION_META, integrationCards, type IntegrationCard } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

const CATEGORY_ICON: Record<IntegrationCard['category'], React.ReactNode> = {
  通知: <MailOutlined />,
  代码源: <CodeOutlined />,
  监控: <MonitorOutlined />,
  制品: <RocketOutlined />,
};

/** G1 集成中心：外部系统连接状态（代码源/制品库/监控）与通知渠道配置；凭证一律脱敏。 */
export default function IntegrationHub() {
  const { message } = App.useApp();
  const [testing, setTesting] = useState<string | null>(null);
  const cards = integrationCards(Date.now());

  return (
    <PageShell
      title="集成中心"
      domain="集成域 G1"
      desc="外部系统连接与通知渠道：连接状态、最近同步、测试连通；端点仅展示脱敏形态。"
    >
      <Row gutter={[14, 14]}>
        {cards.map((card) => {
          const meta = INTEGRATION_META[card.status];
          return (
            <Col xs={24} md={12} key={card.id}>
              <Card
                size="small"
                style={{ height: '100%' }}
                title={
                  <Space size={8}>
                    {CATEGORY_ICON[card.category]}
                    <Text strong>{card.name}</Text>
                    <Tag>{card.category}</Tag>
                  </Space>
                }
                extra={<Tag color={meta.tone}>{meta.label}</Tag>}
              >
                <div style={{ fontSize: 12, color: 'var(--text-2)', fontFamily: 'var(--mono)', marginBottom: 6 }}>{card.endpoint}</div>
                <div style={{ fontSize: 12.5, marginBottom: 10 }}>{card.note}</div>
                <Space size={8} wrap>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    <ApiOutlined /> 最近同步：{card.lastSync ? fmtDate(card.lastSync) : '—'}
                  </Text>
                  <Button
                    size="small"
                    icon={<ToolOutlined />}
                    loading={testing === card.id}
                    onClick={() => {
                      setTesting(card.id);
                      setTimeout(() => {
                        setTesting(null);
                        message[card.status === 'error' ? 'error' : 'success'](
                          card.status === 'error' ? '连通性测试失败（演示：模拟异常）' : '连通性测试通过（演示）',
                        );
                      }, 700);
                    }}
                    aria-label={`测试 ${card.name} 连通性`}
                  >
                    测试连通
                  </Button>
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Section title="纪律与成熟化方向" style={{ marginTop: 16 }}>
        <HintText>
          端点/凭证一律脱敏展示（与 E1 环境变量同纪律）；异常连接（监控）会阻塞 S1 事故自动创建与 D2 通知推送。
          成熟化时按 `.ai/env/*.yaml` 三态适配真实端点，严禁明文凭证入库。
        </HintText>
      </Section>
    </PageShell>
  );
}
