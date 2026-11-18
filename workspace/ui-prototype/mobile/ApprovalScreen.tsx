import { useMemo, useState } from 'react';
import { TextInput, View, Text, StyleSheet } from 'react-native';
import {
  approvalFlow,
  FLOW_RULES,
  fmtDate,
  type ApprovalStage,
  type Release,
} from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const DECISION_LABEL: Record<ApprovalStage['decision'], { color: string; label: string }> = {
  approved: { color: TONE_COLOR.success, label: '已通过' },
  rejected: { color: TONE_COLOR.danger, label: '已驳回' },
  pending: { color: TONE_COLOR.muted, label: '待审批' },
};

/** A1 审批详情（APP 形态）：审批链 + 意见 + 通过/驳回（原型仅本地生效）。 */
export default function ApprovalScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [releaseId, setReleaseId] = useState(releases[0]?.id ?? '');
  const [comment, setComment] = useState('');
  const [localFlow, setLocalFlow] = useState<Record<string, ApprovalStage[]>>({});
  const [flash, showFlash] = useFlash();

  const release: Release | undefined = releases.find((r) => r.id === releaseId);
  const base = useMemo(() => (release ? approvalFlow(release, Date.now()) : []), [release]);
  const flow = localFlow[releaseId] ?? base;
  const rule = FLOW_RULES.find((x) => x.env === release?.env);
  const pending = flow.find((s) => s.decision === 'pending');

  const decide = (decision: 'approved' | 'rejected') => {
    if (!pending) return;
    if (!comment.trim()) {
      showFlash('请填写审批意见后再提交');
      return;
    }
    setLocalFlow((cur) => ({
      ...cur,
      [releaseId]: flow.map((s) =>
        s.key === pending.key ? { ...s, decision, comment: comment.trim(), at: new Date().toISOString() } : s,
      ),
    }));
    setComment('');
    showFlash(`原型演示：${pending.label} ${decision === 'approved' ? '通过' : '驳回'}（仅本地生效）`);
  };

  return (
    <ProtoShell title="审批详情" domain="发布单域 A1" desc="审批链状态与意见流；通过/驳回仅本地生效。" onBack={onBack} flash={flash}>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {releases.slice(0, 8).map((r) => (
          <Chip
            key={r.id}
            label={r.id}
            active={r.id === releaseId}
            onPress={() => {
              setReleaseId(r.id);
              setComment('');
            }}
          />
        ))}
      </View>

      {release ? (
        <>
          <Section title={`审批链 · ${release.id}（${release.env}）`}>
            <View style={{ gap: 10 }}>
              {flow.map((s) => (
                <View key={s.key} style={styles.stage}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                    <Tag label={DECISION_LABEL[s.decision].label} color={DECISION_LABEL[s.decision].color} />
                    <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700', flex: 1 }}>{s.label}</Text>
                    <MonoText>{s.approver}</MonoText>
                  </View>
                  <Text style={{ color: palette.text2, fontSize: 12, lineHeight: 18 }}>{s.comment}</Text>
                  {s.at ? <MonoText color={palette.muted}>{fmtDate(s.at)}</MonoText> : null}
                </View>
              ))}
            </View>
            <MonoText color={palette.muted}>
              {release.env} 规则：至少 {rule?.minApprovers ?? 0} 人审批{rule?.autoOnPass ? '，通过后自动流转' : ''}
            </MonoText>
          </Section>

          <Section title={pending ? `处理：${pending.label}` : '处理'}>
            {pending ? (
              <>
                <TextInput
                  value={comment}
                  onChangeText={setComment}
                  placeholder="审批意见（必填）"
                  placeholderTextColor={palette.muted}
                  multiline
                  accessibilityLabel="审批意见输入"
                  style={[styles.input, { color: palette.text }]}
                />
                <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}>
                  <Button label="通过" onPress={() => decide('approved')} small />
                  <Button label="驳回" variant="danger" onPress={() => decide('rejected')} small />
                </View>
              </>
            ) : (
              <Text style={{ color: palette.muted, fontSize: 13 }}>没有待处理的审批阶段。</Text>
            )}
          </Section>
        </>
      ) : (
        <Text style={{ color: palette.muted }}>请选择发布单。</Text>
      )}
    </ProtoShell>
  );
}

const styles = StyleSheet.create({
  stage: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
    gap: 2,
  },
  input: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    backgroundColor: palette.surface,
    padding: 10,
    minHeight: 72,
    textAlignVertical: 'top',
    fontSize: 13,
  },
});
