// 用户域原型数据层 —— 沿用 data.ts / visual.ts 纪律：确定性、纯函数、零凭证。
// 从正式 store 推导成员工作量与个人档案（负责人字段 owner、审批链、审计流），供 U1/U2/U3 消费。
import type { AppState, Release } from '../types';
import { approvalFlow, auditTrail, MEMBERS, type ApprovalStage, type AuditEntry, type Member } from './data';

export interface ApprovalRecord {
  releaseId: string;
  releaseTitle: string;
  stageKey: ApprovalStage['key'];
  stageLabel: string;
  approver: string;
  decision: ApprovalStage['decision'];
  comment: string;
  at: string | null;
}

export interface MemberDirectoryRow {
  member: Member;
  ownedTotal: number;
  ownedActive: number;
  ownedPublished: number;
  pendingApprovals: number;
  signedApprovals: number;
  lastActiveAt: string | null;
}

export interface MemberProfile {
  member: Member;
  ownedReleases: Release[];
  approvalRecords: ApprovalRecord[];
  pendingApprovals: ApprovalRecord[];
  audit: AuditEntry[];
  lastActiveAt: string | null;
}

const OWNER_ACTIVE: Release['status'][] = ['draft', 'building', 'testing', 'approved'];

export function collectApprovals(state: Pick<AppState, 'releases'>, now: number, who?: string): ApprovalRecord[] {
  const records: ApprovalRecord[] = [];
  for (const r of state.releases) {
    for (const s of approvalFlow(r, now)) {
      if (who && s.approver !== who) continue;
      records.push({
        releaseId: r.id,
        releaseTitle: r.title,
        stageKey: s.key,
        stageLabel: s.label,
        approver: s.approver,
        decision: s.decision,
        comment: s.comment,
        at: s.at,
      });
    }
  }
  return records.sort((a, b) => (b.at ?? '').localeCompare(a.at ?? ''));
}

/** 成员目录：从正式 store 推导每个成员的负责量与审批负荷（覆盖 MEMBERS 全量）。 */
export function memberDirectory(state: Pick<AppState, 'releases'>, now: number): MemberDirectoryRow[] {
  const audit = auditTrail(state, now);
  return MEMBERS.map((member) => {
    const owned = state.releases.filter((r) => r.owner === member.name);
    const records = collectApprovals(state, now, member.name);
    const lastAudit = audit.find((a) => a.actor === member.name);
    return {
      member,
      ownedTotal: owned.length,
      ownedActive: owned.filter((r) => OWNER_ACTIVE.includes(r.status)).length,
      ownedPublished: owned.filter((r) => r.status === 'published').length,
      pendingApprovals: records.filter((x) => x.decision === 'pending').length,
      signedApprovals: records.filter((x) => x.decision !== 'pending').length,
      lastActiveAt: lastAudit?.at ?? null,
    };
  });
}

/** 个人档案：某成员的发布单 / 审批记录 / 待办 / 操作留痕；成员不在册时返回 null。 */
export function memberProfile(state: Pick<AppState, 'releases'>, name: string, now: number): MemberProfile | null {
  const member = MEMBERS.find((m) => m.name === name);
  if (!member) return null;
  const ownedReleases = state.releases
    .filter((r) => r.owner === name)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  const approvalRecords = collectApprovals(state, now, name);
  const audit = auditTrail(state, now).filter((a) => a.actor === name);
  return {
    member,
    ownedReleases,
    approvalRecords,
    pendingApprovals: approvalRecords.filter((x) => x.decision === 'pending'),
    audit,
    lastActiveAt: audit[0]?.at ?? null,
  };
}
