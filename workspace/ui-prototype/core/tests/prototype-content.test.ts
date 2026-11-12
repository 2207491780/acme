import { describe, it, expect } from 'vitest';
import {
  announcements,
  backupSnapshots,
  docCards,
  FEATURE_FLAGS,
  FLAG_SCOPE_LABEL,
  onboardingAutoState,
  ONBOARDING_STEPS,
  seedState,
} from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');
const state = seedState(now);

describe('prototype content data', () => {
  it('should_return_identical_content_data_when_called_twice_with_same_inputs', () => {
    expect(announcements(now)).toEqual(announcements(now));
    expect(docCards(now)).toEqual(docCards(now));
    expect(backupSnapshots(state, now)).toEqual(backupSnapshots(state, now));
  });

  it('should_sort_announcements_pinned_first_then_by_recency', () => {
    const list = announcements(now);
    expect(list[0].pinned).toBe(true);
    expect(list.slice(1).every((a) => !a.pinned)).toBe(true);
    expect(list.slice(1)[0].at >= list.slice(1)[1].at).toBe(true);
    for (const a of list) {
      expect(a.body.length).toBeGreaterThan(0);
    }
  });

  it('should_have_owner_and_impact_on_every_feature_flag', () => {
    expect(FEATURE_FLAGS.length).toBeGreaterThanOrEqual(4);
    for (const flag of FEATURE_FLAGS) {
      expect(flag.owner.length).toBeGreaterThan(0);
      expect(flag.impact.length).toBeGreaterThan(0);
      expect(Object.keys(FLAG_SCOPE_LABEL)).toContain(flag.scope);
      expect(typeof flag.enabled).toBe('boolean');
    }
    const keys = FEATURE_FLAGS.map((f) => f.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('should_auto_detect_onboarding_steps_from_real_store_state', () => {
    for (const step of ONBOARDING_STEPS) {
      expect(step.target.startsWith('/')).toBe(true);
      const detected = onboardingAutoState(state, step);
      if (step.auto === 'has-published') expect(detected).toBe(true);
      if (step.auto === 'has-approved') expect(detected).toBe(true);
      if (step.auto === 'has-artifact') expect(detected).toBe(true);
      if (!step.auto) expect(detected).toBeNull();
    }
    const empty = onboardingAutoState({ releases: [] }, ONBOARDING_STEPS.find((s) => s.auto === 'has-published')!);
    expect(empty).toBe(false);
  });

  it('should_build_backup_snapshots_reflecting_current_scale', () => {
    const snaps = backupSnapshots(state, now);
    expect(snaps).toHaveLength(3);
    for (const s of snaps) {
      expect(s.releases).toBe(state.releases.length);
      expect(s.apps).toBe(state.apps.length);
      expect(s.sizeKb).toBeGreaterThan(0);
    }
  });
});
