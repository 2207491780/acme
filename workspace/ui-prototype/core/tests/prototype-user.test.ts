import { describe, it, expect } from 'vitest';
import { collectApprovals, memberDirectory, memberProfile, seedState } from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');
const state = seedState(now);

describe('prototype user data', () => {
  it('should_return_identical_user_data_when_called_twice_with_same_inputs', () => {
    expect(memberDirectory(state, now)).toEqual(memberDirectory(state, now));
    expect(memberProfile(state, '何青', now)).toEqual(memberProfile(state, '何青', now));
    expect(collectApprovals(state, now, '何青')).toEqual(collectApprovals(state, now, '何青'));
  });

  it('should_cover_every_member_and_partition_release_ownership', () => {
    const directory = memberDirectory(state, now);
    expect(directory).toHaveLength(6);
    const ownedTotal = directory.reduce((n, row) => n + row.ownedTotal, 0);
    expect(ownedTotal).toBe(state.releases.length);
    for (const row of directory) {
      expect(row.ownedActive + row.ownedPublished).toBeLessThanOrEqual(row.ownedTotal);
      expect(row.signedApprovals + row.pendingApprovals)
        .toBe(collectApprovals(state, now, row.member.name).length);
    }
  });

  it('should_collect_pending_and_signed_approvals_consistently', () => {
    const profile = memberProfile(state, '何青', now)!;
    expect(profile.member.name).toBe('何青');
    expect(profile.pendingApprovals.every((x) => x.decision === 'pending')).toBe(true);
    expect(profile.pendingApprovals.length + profile.approvalRecords.filter((x) => x.decision !== 'pending').length)
      .toBe(profile.approvalRecords.length);
    for (const record of profile.approvalRecords) {
      expect(state.releases.some((r) => r.id === record.releaseId)).toBe(true);
    }
  });

  it('should_return_null_profile_when_member_is_unknown', () => {
    expect(memberProfile(state, '不存在的人', now)).toBeNull();
  });

  it('should_sort_owned_releases_by_recency_and_filter_audit_by_actor', () => {
    const profile = memberProfile(state, '林晚', now)!;
    for (let i = 1; i < profile.ownedReleases.length; i += 1) {
      expect(profile.ownedReleases[i - 1].updatedAt >= profile.ownedReleases[i].updatedAt).toBe(true);
    }
    for (const entry of profile.audit) {
      expect(entry.actor).toBe('林晚');
    }
    expect(profile.lastActiveAt).not.toBeNull();
  });
});
