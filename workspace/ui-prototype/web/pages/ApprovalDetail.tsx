import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { App, Button, Card, Input, Select, Space, Steps, Tag, Typography } from 'antd';
import { CheckOutlined, CloseOutlined, SendOutlined } from '@ant-design/icons';
import { approvalFlow, FLOW_RULES, fmtFull, type ApprovalStage } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text, Paragraph } = Typography;

const DECISION_TAG: Record<ApprovalStage['decision'], { color: string; label: string }> = {
  approved: { color: 'success', label: '已通过' },
  rejected: { color: 'error', label: '已驳回' },
  pending: { color: 'default', label: '待审批' },
};

/** A1 审批详情页：审批链、意见、通过/驳回交互（原型仅本地生效）。 */
export default function ApprovalDetail() {
  const { message } = App.useApp();
  const releases = useStore((s) => s.state.releases);
  const [params] = useSearchParams();
  const [releaseId, setReleaseId] = useState(params.get('release') ?? releases[0]?.id ?? '');
  const [comment, setComment] = useState('');
  const [localFlow, setLocalFlow] = useState<ApprovalStage[] | null>(null);

  const release = releases.find((r) => r.id === releaseId);
  const baseFlow = useMemo(() => (release ? approvalFlow(release, Date.now()) : []), [release]);
  const flow = localFlow ?? baseFlow;
  const rule = FLOW_RULES.find((x) => x.env === release?.env);
  const passedCount = flow.filter((s) => s.decision === 'approved').length;
  const pending = flow.find((s) => s.decision === 'pending');

  const decide = (decision: 'approved' | 'rejected') => {
    if (!release || !pending) return;
    if (!comment.trim()) {
      message.warning('请填写审批意见后再提交');
      return;
    }
    setLocalFlow(flow.map((s) => (s.key === pending.key ? { ...s, decision, comment: comment.trim(), at: new Date().toISOString() } : s)));
    setComment('');
    message[decision === 'approved' ? 'success' : 'warning'](
      `原型演示：${pending.label} ${decision === 'approved' ? '通过' : '驳回'}（仅本地生效）`,
    );
  };

  const stepStatus = (stage: ApprovalStage, index: number) =>
    stage.decision === 'approved' ? 'finish' : stage.decision === 'rejected' ? 'error' : index === flow.findIndex((s) => s.decision === 'pending') ? 'process' : 'wait';

  return (
    <PageShell
      title="审批详情"
      domain="发布单域 A1"
      desc="审批链状态、意见流与通过/驳回操作的高保真原型；审批规则取自 B1 流程配置口径。"
      extra={
        <Select
          style={{ width: 280 }}
          value={releaseId || undefined}
          onChange={(v) => {
            setReleaseId(v);
            setLocalFlow(null);
          }}
          placeholder="选择发布单"
          aria-label="选择发布单"
          options={releases.map((r) => ({ value: r.id, label: `${r.id} · ${r.title}` }))}
        />
      }
    >
      {!release ? (
        <EmptyState title="请选择发布单" desc="选择一个发布单查看审批链。" />
      ) : (
        <>
          <Card size="small" style={{ marginBottom: 16 }}>
            <Steps
              size="small"
              items={flow.map((s, i) => ({ title: s.label, description: `${s.approver} · ${DECISION_TAG[s.decision].label}`, status: stepStatus(s, i) }))}
            />
            <HintText>
              {release.env} 环境规则：至少 {rule?.minApprovers ?? 0} 人审批
              {rule?.autoOnPass ? '，通过后自动流转' : '，需人工触发发布'}；当前已通过 {passedCount}/{flow.length}。
            </HintText>
          </Card>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 16 }}>
            <Section title="审批意见流">
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {[...flow].reverse().map((s) => (
                  <div key={s.key} style={{ padding: 12, borderRadius: 12, border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                    <Space wrap style={{ marginBottom: 6 }}>
                      <Tag color={DECISION_TAG[s.decision].color}>{s.label}</Tag>
                      <Text strong>{s.approver}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>{s.role}</Text>
                      {s.at && <Text type="secondary" style={{ fontSize: 12 }}>{fmtFull(s.at)}</Text>}
                    </Space>
                    <Paragraph style={{ marginBottom: 0, fontSize: 13 }}>{s.comment}</Paragraph>
                  </div>
                ))}
              </Space>
            </Section>

            <Section title={pending ? `处理：${pending.label}` : '处理'}>
              {pending ? (
                <>
                  <Input.TextArea
                    rows={4}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder="审批意见（必填）：结论、风险、条件…"
                    maxLength={200}
                    showCount
                    aria-label="审批意见"
                  />
                  <Space style={{ marginTop: 12 }}>
                    <Button type="primary" icon={<CheckOutlined />} onClick={() => decide('approved')}>
                      通过
                    </Button>
                    <Button danger icon={<CloseOutlined />} onClick={() => decide('rejected')}>
                      驳回
                    </Button>
                  </Space>
                  <HintText style={{ marginTop: 12 }}>提交后仅更新本地原型状态，不写入正式数据。</HintText>
                </>
              ) : (
                <EmptyState title="没有待处理的审批" desc="该发布单审批链已完成或已结束。" />
              )}
              <div style={{ marginTop: 16, display: 'flex', gap: 8, color: 'var(--muted)', fontSize: 12 }}>
                <SendOutlined />
                <span>成熟化时接入审批动作 API 与消息通知（D2）。</span>
              </div>
            </Section>
          </div>
        </>
      )}
    </PageShell>
  );
}
