import { useState } from 'react';
import { App, Button, Space, Steps, Tag, Typography } from 'antd';
import { CopyOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { platformShort, TEMPLATES, type PipelineTemplate } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { Kpi, KpiRow, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** B2 流水线模板：阶段构成可视化 + 以模板发起发布（原型仅本地生效）。 */
export default function PipelineTemplates() {
  const { message } = App.useApp();
  const [selected, setSelected] = useState<PipelineTemplate | null>(TEMPLATES[0] ?? null);

  return (
    <PageShell
      title="流水线模板"
      domain="流程配置域 B2"
      desc="按平台维护「构建 → 测试 → 审批 → 发布」的阶段模板；自动阶段标注预估耗时，人工阶段等待签核。"
    >
      <div style={{ display: 'grid', gridTemplateColumns: '300px 1fr', gap: 16 }}>
        <Section title="模板库">
          {TEMPLATES.length === 0 ? (
            <EmptyState title="暂无模板" />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }}>
              {TEMPLATES.map((t) => (
                <Button
                  key={t.id}
                  block
                  type={selected?.id === t.id ? 'primary' : 'default'}
                  style={{ textAlign: 'left', height: 'auto', padding: '8px 12px', justifyContent: 'flex-start' }}
                  onClick={() => setSelected(t)}
                  aria-label={`选择模板 ${t.name}`}
                >
                  <div>
                    <Text strong={selected?.id === t.id} style={{ color: selected?.id === t.id ? '#000' : undefined }}>
                      {t.name}
                    </Text>
                    <div style={{ fontSize: 11, opacity: 0.75 }}>
                      {platformShort(t.platform)} · {t.stages.length} 阶段 · 自动 {t.stages.filter((s) => s.kind === 'auto').length}
                    </div>
                  </div>
                </Button>
              ))}
            </Space>
          )}
        </Section>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {selected && (
            <>
              <KpiRow>
                <Kpi label="自动阶段" value={selected.stages.filter((s) => s.kind === 'auto').length} tone="info" />
                <Kpi label="人工阶段" value={selected.stages.filter((s) => s.kind === 'manual').length} tone="warning" />
                <Kpi
                  label="预估总耗时"
                  value={selected.stages.reduce((n, s) => n + s.estimateMin, 0)}
                  unit="分钟"
                  tone="success"
                />
              </KpiRow>
              <Section
                title={`阶段编排 · ${selected.name}`}
                extra={
                  <Space>
                    <Button size="small" icon={<CopyOutlined />} onClick={() => message.info('原型演示：复制模板仅本地生效')}>
                      复制模板
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      icon={<ThunderboltOutlined />}
                      onClick={() => message.info('原型演示：以模板发起发布仅本地生效')}
                    >
                      以此模板发起
                    </Button>
                  </Space>
                }
              >
                <Steps
                  direction="vertical"
                  size="small"
                  items={selected.stages.map((s) => ({
                    title: (
                      <Space size={6}>
                        <Text strong>{s.label}</Text>
                        <Tag color={s.kind === 'auto' ? 'processing' : 'warning'}>{s.kind === 'auto' ? '自动' : '人工'}</Tag>
                        {s.estimateMin > 0 && <Text type="secondary" style={{ fontSize: 11 }}>约 {s.estimateMin} 分钟</Text>}
                      </Space>
                    ),
                    description:
                      s.kind === 'manual'
                        ? '等待审批签核，超时升级（见 B1 规则）'
                        : `失败即中止流水线，产物不进入下一阶段`,
                  }))}
                />
              </Section>
            </>
          )}
        </div>
      </div>
    </PageShell>
  );
}
