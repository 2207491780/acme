import { compareVersion } from './utils';
import { PLATFORMS, STATUSES, ACTIVE_STATUSES } from './constants';
import type { AppState, Metrics, Query, Release, SortKey } from './types';

export function filterReleases(state: AppState, query: Query): Release[] {
  const q = String(query.search || '').trim().toLowerCase();
  return state.releases.filter((r) => {
    if (query.platform && query.platform !== 'all' && r.platform !== query.platform) return false;
    if (query.env && query.env !== 'all' && r.env !== query.env) return false;
    if (query.status && query.status !== 'all') {
      if (query.status === 'active') {
        if (!ACTIVE_STATUSES.includes(r.status)) return false;
      } else if (r.status !== query.status) return false;
    }
    if (!q) return true;
    const app = state.apps.find((a) => a.id === r.appId);
    const haystack = [r.id, r.title, r.version, r.owner, app?.name, r.notes].join(' ').toLowerCase();
    return haystack.includes(q);
  });
}

export function sortReleases(list: Release[], sort: SortKey, order: string[]): Release[] {
  const arr = [...list];
  const index = new Map(order.map((id, i) => [id, i]));
  switch (sort) {
    case 'updated':
      return arr.sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());
    case 'priority':
      return arr.sort((a, b) => a.priority - b.priority || a.id.localeCompare(b.id));
    case 'version':
      return arr.sort((a, b) => compareVersion(b.version, a.version));
    case 'title':
      return arr.sort((a, b) => a.title.localeCompare(b.title, 'zh-Hans-CN'));
    case 'manual':
    default:
      return arr.sort((a, b) => (index.get(a.id) ?? 9999) - (index.get(b.id) ?? 9999));
  }
}

export function metrics(state: AppState): Metrics {
  const byStatus = Object.fromEntries(STATUSES.map((s) => [s, 0])) as Metrics['byStatus'];
  const byPlatform = Object.fromEntries(PLATFORMS.map((p) => [p, 0])) as Metrics['byPlatform'];
  const today = new Date().toDateString();
  let publishedToday = 0;
  for (const r of state.releases) {
    byStatus[r.status] = (byStatus[r.status] || 0) + 1;
    byPlatform[r.platform] = (byPlatform[r.platform] || 0) + 1;
    if (r.status === 'published' && new Date(r.updatedAt).toDateString() === today) publishedToday += 1;
  }
  return {
    total: state.releases.length,
    active: ACTIVE_STATUSES.reduce((n, s) => n + (byStatus[s] || 0), 0),
    approved: byStatus.approved || 0,
    failed: byStatus.failed || 0,
    published: byStatus.published || 0,
    publishedToday,
    byStatus,
    byPlatform,
  };
}