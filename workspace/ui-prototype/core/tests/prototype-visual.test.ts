import { describe, it, expect } from 'vitest';
import {
  appRadar,
  buildNodes,
  pipelineChain,
  publishHeatmap,
  releaseFunnel,
  releaseGantt,
  seedState,
  type HeatCell,
} from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');
const state = seedState(now);

describe('prototype visual data', () => {
  it('should_return_identical_visual_data_when_called_twice_with_same_inputs', () => {
    expect(publishHeatmap(state, 12, now)).toEqual(publishHeatmap(state, 12, now));
    expect(releaseFunnel(state)).toEqual(releaseFunnel(state));
    expect(appRadar(state, now)).toEqual(appRadar(state, now));
    expect(releaseGantt(state, now)).toEqual(releaseGantt(state, now));
    expect(buildNodes(state, now)).toEqual(buildNodes(state, now));
  });

  it('should_bucket_heat_levels_when_activity_varies', () => {
    const cells = publishHeatmap(state, 12, now);
    expect(cells).toHaveLength(12 * 7);
    for (const c of cells as HeatCell[]) {
      expect([0, 1, 2, 3, 4]).toContain(c.level);
      expect(c.level > 0).toBe(c.created + c.published * 2 > 0);
    }
    expect(cells.at(-1)!.date).toBe('2026-11-11');
  });

  it('should_sum_funnel_counts_with_off_funnel_statuses_excluded', () => {
    const funnel = releaseFunnel(state);
    expect(funnel.map((s) => s.label)).toEqual(['草稿', '构建中', '测试中', '待发布', '已发布']);
    const inFunnel = funnel.reduce((n, s) => n + s.count, 0);
    const offFunnel = state.releases.filter((r) => r.status === 'failed' || r.status === 'cancelled').length;
    expect(inFunnel + offFunnel).toBe(state.releases.length);
  });

  it('should_bound_radar_scores_and_cover_every_app', () => {
    const radars = appRadar(state, now);
    expect(radars).toHaveLength(state.apps.length);
    for (const r of radars) {
      expect(r.axes).toHaveLength(5);
      for (const axis of r.axes) {
        expect(axis.score).toBeGreaterThanOrEqual(0);
        expect(axis.score).toBeLessThanOrEqual(100);
      }
    }
  });

  it('should_clamp_gantt_rows_into_window_and_extend_active_rows_to_today', () => {
    const gantt = releaseGantt(state, now, 30, 8);
    expect(gantt.rows.length).toBeLessThanOrEqual(8);
    for (const row of gantt.rows) {
      expect(row.start >= gantt.start).toBe(true);
      expect(row.end <= gantt.today).toBe(true);
      expect(row.start <= row.end).toBe(true);
      if (['draft', 'building', 'testing', 'approved'].includes(row.status)) {
        expect(row.end).toBe(gantt.today);
      }
    }
  });

  it('should_mark_chain_progress_and_failure_when_status_varies', () => {
    const building = pipelineChain({ status: 'building', progress: 55 });
    expect(building.find((s) => s.key === 'draft')?.state).toBe('done');
    expect(building.find((s) => s.key === 'building')?.state).toBe('active');
    expect(building.find((s) => s.key === 'building')?.note).toBe('进度 55%');
    expect(building.find((s) => s.key === 'published')?.state).toBe('pending');

    const failed = pipelineChain({ status: 'failed', progress: 0 });
    expect(failed.find((s) => s.key === 'testing')?.state).toBe('failed');

    const published = pipelineChain({ status: 'published', progress: 100 });
    expect(published.every((s) => s.state === 'done')).toBe(true);
  });

  it('should_distribute_node_statuses_with_some_healthy_capacity', () => {
    const nodes = buildNodes(state, now, 24);
    expect(nodes).toHaveLength(24);
    const statuses = new Set(nodes.map((n) => n.status));
    expect(statuses.size).toBeGreaterThanOrEqual(2);
    for (const n of nodes) {
      expect(n.load).toBeGreaterThanOrEqual(0);
      expect(n.load).toBeLessThanOrEqual(100);
      if (n.status === 'building') expect(n.job).toMatch(/^REL-/);
      else expect(n.job).toBeUndefined();
    }
  });
});
