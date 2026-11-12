import { describe, it, expect } from 'vitest';
import {
  approvalFlow,
  buildLog,
  createRng,
  cycleTimeStats,
  ENV_VARS,
  findConflicts,
  hashSeed,
  healthSeries,
  maskSecret,
  notices,
  orchestrationGraph,
  publishTrend,
  rollbackOptions,
  threads,
  topoSort,
  upcomingSchedule,
  auditTrail,
} from '../src';
import { seedState } from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');

describe('prototype data determinism', () => {
  it('should_return_identical_mock_data_when_called_twice_with_same_inputs', () => {
    const state = seedState(now);
    const release = state.releases[0];
    expect(approvalFlow(release, now)).toEqual(approvalFlow(release, now));
    expect(buildLog(release)).toEqual(buildLog(release));
    expect(notices(now)).toEqual(notices(now));
    expect(upcomingSchedule(state, now)).toEqual(upcomingSchedule(state, now));
    expect(threads(state, now)).toEqual(threads(state, now));
    expect(auditTrail(state, now)).toEqual(auditTrail(state, now));
    expect(healthSeries('web', now)).toEqual(healthSeries('web', now));
    expect(rollbackOptions(state, state.releases[0], now)).toEqual(rollbackOptions(state, state.releases[0], now));
  });

  it('should_seed_same_value_sequence_when_rng_created_from_same_seed', () => {
    const a = createRng('x');
    const b = createRng('x');
    const seq = Array.from({ length: 16 }, () => a());
    expect(seq).toEqual(Array.from({ length: 16 }, () => b()));
    expect(seq.every((v) => v >= 0 && v < 1)).toBe(true);
    expect(hashSeed('acme')).toBe(hashSeed('acme'));
    expect(hashSeed('acme')).not.toBe(hashSeed('acme2'));
  });
});

describe('prototype derived views', () => {
  const state = seedState(now);

  it('should_mark_pending_stages_when_release_is_testing', () => {
    const testing = state.releases.find((r) => r.status === 'testing')!;
    const flow = approvalFlow(testing, now);
    expect(flow).toHaveLength(3);
    expect(flow[0].decision).toBe('approved');
    expect(flow.slice(1).every((s) => s.decision === 'pending')).toBe(true);
    expect(flow[1].at).toBeNull();
  });

  it('should_inject_test_failure_and_stop_pipeline_when_release_is_failed', () => {
    const failed = state.releases.find((r) => r.status === 'failed')!;
    const log = buildLog(failed);
    const errors = log.filter((l) => l.level === 'error');
    expect(errors.length).toBeGreaterThanOrEqual(2);
    expect(log.at(-1)?.stage).toBe('test');
    expect(log.at(-1)?.level).toBe('error');
  });

  it('should_complete_all_stages_when_release_is_published', () => {
    const published = state.releases.find((r) => r.status === 'published')!;
    const log = buildLog(published);
    expect(log.at(-1)?.stage).toBe('deploy');
    expect(approvalFlow(published, now).every((s) => s.decision === 'approved')).toBe(true);
  });

  it('should_count_trend_points_per_day_when_publishing', () => {
    const trend = publishTrend(state, 7, now);
    expect(trend).toHaveLength(7);
    expect(trend.at(-1)!.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const publishedTotal = trend.reduce((n, p) => n + p.published, 0);
    expect(publishedTotal).toBeLessThanOrEqual(state.releases.length);
    expect(cycleTimeStats(state).every((s) => s.count >= 0)).toBe(true);
  });

  it('should_detect_same_day_same_env_conflicts_when_scheduling', () => {
    const items = upcomingSchedule(state, now);
    expect(items.length).toBeGreaterThan(0);
    const conflicts = findConflicts([
      { releaseId: 'r1', title: 'A', date: '2026-11-12', env: 'prod', window: 'morning', status: 'approved' },
      { releaseId: 'r2', title: 'B', date: '2026-11-12', env: 'prod', window: 'night', status: 'approved' },
      { releaseId: 'r3', title: 'C', date: '2026-11-12', env: 'dev', window: 'morning', status: 'draft' },
    ]);
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0].map((x) => x.releaseId)).toEqual(['r1', 'r2']);
  });

  it('should_topologically_order_orchestration_and_report_cycles', () => {
    const nodes = orchestrationGraph(state);
    const { order, cycles } = topoSort(nodes);
    expect(order.length + cycles.length).toBe(nodes.length);
    const position = new Map(order.map((id, i) => [id, i]));
    for (const node of nodes) {
      for (const dep of node.dependsOn) {
        if (position.has(dep) && position.has(node.releaseId)) {
          expect(position.get(dep)!).toBeLessThan(position.get(node.releaseId)!);
        }
      }
    }
    const cyclic = topoSort([
      { releaseId: 'a', title: 'a', appId: 'web', dependsOn: ['b'], status: 'draft' },
      { releaseId: 'b', title: 'b', appId: 'web', dependsOn: ['a'], status: 'draft' },
    ]);
    expect(cyclic.order).toEqual([]);
    expect(cyclic.cycles).toEqual(['a', 'b']);
  });

  it('should_mask_secrets_and_never_expose_full_values', () => {
    expect(maskSecret('abc')).toBe('•••');
    expect(maskSecret('super-secret-token-3f2a')).toContain('•');
    expect(maskSecret('super-secret-token-3f2a').endsWith('3f2a')).toBe(true);
    for (const v of ENV_VARS) {
      if (v.secret) expect(v.value).toContain('•');
    }
  });

  it('should_keep_health_series_within_bounds_when_app_varies', () => {
    const web = healthSeries('web', now);
    const mobile = healthSeries('mobile', now);
    expect(web).toHaveLength(24);
    for (const p of web) expect(p.uptime).toBeGreaterThanOrEqual(99.5);
    for (const p of mobile) expect(p.uptime).toBeLessThan(99.9);
  });

  it('should_produce_audit_and_notice_items_with_valid_shape', () => {
    for (const n of notices(now)) {
      expect(['approval', 'build', 'mention', 'system']).toContain(n.category);
      expect(n.title.length).toBeGreaterThan(0);
    }
    const audit = auditTrail(state, now, 10);
    expect(audit).toHaveLength(10);
    for (const e of audit) expect(state.releases.some((r) => r.id === e.target)).toBe(true);
  });
});
