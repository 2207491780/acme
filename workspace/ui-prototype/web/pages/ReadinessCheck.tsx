import { useMemo, useState } from 'react';
import { Button, Progress, Select, Space, Tag, Typography } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, SafetyCertificateOutlined } from '@ant-design/icons';
import { readinessReport, type ReadinessReport } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** R1 发布就绪度检查（Go/No-Go）：六项检查矩阵 + 评分与阻塞判定，全部由 store 与 A1 口径推导。 */
export default function ReadinessCheck() {
  const state = useStore((s) => s.state);
  const [releaseId, setReleaseId] = useState(state.releases[0]?.id ?? '');
  const report: ReadinessReport | null = useMemo(
    () => readinessReport(state, releaseId, Date.now()),
    [state, releaseId],
  );

  return (
    <PageShell
      title="发布就绪度检查"
      domain="发布工程域 R1"
      desc="Go/No-Go 门禁：构建、测试、审批、产物、窗口、回滚预案六项；prod 冻结期与缺签为硬阻塞。"
      extra={
        <Select
          style={{ width: 280 }}
          value={releaseId || undefined}
          onChange={setReleaseId}
          placeholder="选择发布单"
          aria-label="选择发布单"
          options={state.releases.map((r) => ({ value: r.id, label: `${r.id} · ${r.title}` }))}
        />
      }
    >
      {!report ? (
        <EmptyState title="请选择发布单" />
      ) : (
        <>
          <Section
            title={
              <Space size={8}>
                <SafetyCertificateOutlined style={{ color: report.ready ? 'var(--acid)' : 'var(--amber)' }} />
                <Text strong>检查矩阵 · {report.releaseId}</Text>
                <Tag color={report.ready ? 'success' : 'warning'}>{report.ready ? 'GO' : 'NO-GO'}</Tag>
              </Space>
            }
            extra={<Progress type="circle" size={44} percent={report.score} strokeColor={report.score === 100 ? 'var(--acid)' : 'var(--amber)'} />}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {report.items.map((item) => (
                <div key={item.key} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                  {item.pass ? (
                    <CheckCircleFilled style={{ color: 'var(--acid)', fontSize: 16, marginTop: 2 }} />
                  ) : (
                    <CloseCircleFilled style={{ color: item.blocking ? 'var(--red)' : 'var(--amber)', fontSize: 16, marginTop: 2 }} />
                  )}
                  <div style={{ flex: 1 }}>
                    <Space size={6} wrap>
                      <Text strong>{item.label}</Text>
                      {item.blocking && <Tag color="error">阻塞项</Tag>}
                      {!item.pass && !item.blocking && <Tag color="warning">非阻塞</Tag>}
                    </Space>
                    <div style={{ fontSize: 12, color: 'var(--text-2)', marginTop: 2 }}>{item.note}</div>
                  </div>
                </div>
              ))}
            </div>
          </Section>
          <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
            <Button type="primary" disabled={!report.ready} onClick={() => window.alert('原型演示：执行发布仅本地生效')}>
              确认执行发布（GO）
            </Button>
            <Button disabled={report.ready}>延期 / 整改（NO-GO）</Button>
          </div>
          <HintText style={{ marginTop: 12 }}>
            评分 = 通过项占比；ready = 无阻塞失败且满分。成熟化时阻塞项支持豁免签核（双人复核）。
          </HintText>
        </>
      )}
    </PageShell>
  );
}
