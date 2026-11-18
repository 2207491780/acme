import { describe, it, expect } from 'vitest';
import {
  artifactInventory,
  deploymentTopology,
  envOverview,
  globalSearch,
  grayPlans,
  incidents,
  INTEGRATION_META,
  integrationCards,
  MEMBERS,
  opsRatio,
  readinessReport,
  releaseDiff,
  seedState,
} from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');
const state = seedState(now);

describe('prototype ops data', () => {
  it('should_return_identical_ops_data_when_called_twice_with_same_inputs', () => {
    expect(readinessReport(state, state.releases[0].id, now)).toEqual(readinessReport(state, state.releases[0].id, now));
    expect(incidents(state, now)).toEqual(incidents(state, now));
    expect(grayPlans(state, now)).toEqual(grayPlans(state, now));
    expect(releaseDiff(state, state.releases[0].id, state.releases[1].id)).toEqual(
      releaseDiff(state, state.releases[0].id, state.releases[1].id),
    );
  });

  it('should_score_readiness_and_block_prod_freeze_window', () => {
    const approved = state.releases.find((r) => r.status === 'approved')!;
    const report = readinessReport(state, approved.id, now)!;
    expect(report.items).toHaveLength(6);
    expect(report.score).toBeGreaterThanOrEqual(0);
    expect(report.score).toBeLessThanOrEqual(100);
    const build = report.items.find((i) => i.key === 'build')!;
    expect(build.pass).toBe(true);
    const freeze = readinessReport(state, approved.id, Date.parse('2026-11-11T23:30:00+08:00'));
    expect(freeze).not.toBeNull();
    expect(readinessReport(state, 'missing', now)).toBeNull();
  });

  it('should_aggregate_artifacts_sorted_by_recency', () => {
    const rows = artifactInventory(state);
    expect(rows.length).toBeGreaterThan(0);
    for (let i = 1; i < rows.length; i += 1) {
      expect(rows[i - 1].createdAt >= rows[i].createdAt).toBe(true);
    }
    for (const row of rows) {
      expect(state.releases.some((r) => r.id === row.releaseId && r.artifacts.some((a) => a.name === row.name))).toBe(true);
    }
  });

  it('should_diff_two_releases_field_by_field', () => {
    const a = state.releases[0];
    const b = state.releases[2]; // 不同版本号，保证存在差异字段
    const diff = releaseDiff(state, a.id, b.id);
    expect(diff.fields.length).toBeGreaterThan(0);
    for (const f of diff.fields) {
      expect(f.changed).toBe(f.a !== f.b);
    }
    expect(diff.fields.find((f) => f.field === '版本')!.changed).toBe(true);
    expect(releaseDiff(state, a.id, a.id).fields.every((f) => !f.changed)).toBe(true);
  });

  it('should_summarize_three_envs_with_freeze_policy', () => {
    const envs = envOverview(state);
    expect(envs.map((e) => e.env)).toEqual(['dev', 'test', 'prod']);
    for (const e of envs) {
      if (e.deployedVersion) expect(e.publishes).toBeGreaterThan(0);
      expect(e.varCount).toBeGreaterThan(0);
    }
    expect(envs.find((e) => e.env === 'prod')!.freeze).toContain('双人审批');
  });

  it('should_build_topology_with_apps_and_channels', () => {
    const { nodes, edges } = deploymentTopology(state);
    expect(nodes.filter((n) => n.kind === 'app')).toHaveLength(3);
    expect(nodes.filter((n) => n.kind === 'channel')).toHaveLength(3);
    expect(edges.some((e) => e.from === 'web' && e.to === 'desktop')).toBe(true);
    for (const e of edges) {
      expect(nodes.some((n) => n.id === e.from)).toBe(true);
      expect(nodes.some((n) => n.id === e.to)).toBe(true);
    }
  });

  it('should_derive_incidents_from_failed_releases_with_mttr_stats', () => {
    const { list, mttrAvgHours, open } = incidents(state, now);
    const failedCount = state.releases.filter((r) => r.status === 'failed').length;
    expect(list).toHaveLength(failedCount);
    expect(open).toBe(list.filter((x) => !x.resolved).length);
    const resolved = list.filter((x) => x.mttrHours !== null);
    if (resolved.length) {
      expect(mttrAvgHours).toBe(Number((resolved.reduce((n, x) => n + x.mttrHours!, 0) / resolved.length).toFixed(1)));
    }
    for (const inc of list) {
      expect(['P1', 'P2', 'P3']).toContain(inc.severity);
    }
  });

  it('should_build_gray_plans_with_three_stages_and_bounds', () => {
    const plans = grayPlans(state, now);
    expect(plans.length).toBeGreaterThan(0);
    for (const plan of plans) {
      expect(plan.stages.map((s) => s.percent)).toEqual([5, 50, 100]);
      for (const stage of plan.stages) {
        if (stage.state !== 'pending') {
          expect(stage.successRate).toBeGreaterThanOrEqual(0);
          expect(stage.successRate).toBeLessThanOrEqual(100);
        }
      }
    }
  });

  it('should_search_across_releases_apps_and_members', () => {
    const members = MEMBERS.map((m) => ({ name: m.name, email: m.email }));
    expect(globalSearch(state, members, 'REL', 20).length).toBeGreaterThan(0);
    expect(globalSearch(state, members, 'lin.wan', 20).some((h) => h.kind === 'member')).toBe(true);
    expect(globalSearch(state, members, 'web', 20).some((h) => h.kind === 'app')).toBe(true);
    expect(globalSearch(state, members, '   ', 20)).toEqual([]);
    expect(globalSearch(state, members, 'zzz-no-match', 8)).toEqual([]);
    expect(opsRatio(1, 4)).toBe(0.25);
    expect(INTEGRATION_META.connected.tone).toBe('success');
    expect(integrationCards(now)).toHaveLength(5);
  });
});
