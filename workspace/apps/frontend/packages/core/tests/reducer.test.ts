import { describe, it, expect, vi } from 'vitest';
import {
  seedState,
  reduce,
  loadState,
  saveState,
  parseState,
  filterReleases,
  sortReleases,
  metrics,
  validateReleaseInput,
  STORAGE_KEY,
} from '../src';
import type { AppState, ReleaseInput, StorageLike } from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');

const memoryStorage = (): StorageLike => {
  const map = new Map<string, string>();
  return {
    getItem: (k) => (map.has(k) ? map.get(k)! : null),
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
  };
};

const validInput: ReleaseInput = {
  title: '新发布',
  appId: 'web',
  version: '1.5.0',
  env: 'test',
  owner: '测试员',
  priority: 3,
  notes: '',
};

describe('reducer', () => {
  it('should_seed_believable_records_when_state_is_empty', () => {
    const state = seedState(now);
    expect(state.apps.length).toBe(3);
    expect(state.releases.length).toBeGreaterThanOrEqual(12);
    expect(state.order.length).toBe(state.releases.length);
    expect(state.releases.every((r) => r.timeline.length >= 1)).toBe(true);
  });

  it('should_create_release_when_input_is_valid', () => {
    const state = seedState(now);
    const next = reduce(state, { type: 'CREATE_RELEASE', payload: validInput }, now);
    expect(next.releases.length).toBe(state.releases.length + 1);
    const created = next.releases[0];
    expect(created.title).toBe('新发布');
    expect(created.platform).toBe('web');
    expect(created.status).toBe('draft');
    expect(next.order[0]).toBe(created.id);
  });

  it('should_reject_release_when_version_is_invalid', () => {
    const state = seedState(now);
    const errors = validateReleaseInput({ ...validInput, version: 'abc' }, state.apps);
    expect(errors.version).toBeTruthy();
    const next = reduce(state, { type: 'CREATE_RELEASE', payload: { ...validInput, version: 'abc' } }, now);
    expect(next).toBe(state);
  });

  it('should_update_release_when_payload_is_valid', () => {
    const state = seedState(now);
    const release = state.releases[0];
    const id = release.id;
    const next = reduce(state, { type: 'UPDATE_RELEASE', id, payload: { ...release, title: '改标题' } }, now);
    const updated = next.releases.find((r) => r.id === id)!;
    expect(updated.title).toBe('改标题');
    expect(updated.timeline.some((t) => t.action === '编辑发布单')).toBe(true);
  });

  it('should_restore_deleted_releases_when_undo_is_dispatched', () => {
    const state = seedState(now);
    const target = state.releases[2];
    const items = [{ release: target, index: 2 }];
    const deleted = reduce(state, { type: 'DELETE_RELEASES', ids: [target.id] }, now);
    expect(deleted.releases.some((r) => r.id === target.id)).toBe(false);
    const restored = reduce(deleted, { type: 'RESTORE_RELEASES', items }, now);
    expect(restored.releases[2].id).toBe(target.id);
    expect(restored.order.includes(target.id)).toBe(true);
  });

  it('should_preserve_deleted_release_when_creating_then_undoing_highest_id_deletion', () => {
    const state = seedState(now);
    const target = state.releases.reduce((max, release) =>
      Number(release.id.slice(4)) > Number(max.id.slice(4)) ? release : max);
    const index = state.releases.indexOf(target);
    const deleted = reduce(state, { type: 'DELETE_RELEASES', ids: [target.id] }, now);
    const created = reduce(deleted, { type: 'CREATE_RELEASE', payload: validInput }, now);
    const newRelease = created.releases[0];
    expect(newRelease.id).toMatch(/^REL-/);
    expect(newRelease.id).not.toBe(target.id);
    const restored = reduce(created, {
      type: 'RESTORE_RELEASES', items: [{ release: target, index }],
    }, now);
    expect(restored.releases.find((release) => release.id === target.id)).toEqual(target);
    expect(restored.releases.find((release) => release.id === newRelease.id)).toEqual(newRelease);
    expect(restored.releases).toHaveLength(state.releases.length + 1);
    expect(new Set(restored.order).size).toBe(restored.releases.length);
    expect(restored.activity[0].text).toBe('恢复 1 个发布单');
  });

  it('should_count_only_removed_releases_when_delete_ids_are_duplicate_or_missing', () => {
    const state = seedState(now);
    const id = state.releases[0].id;
    const next = reduce(state, { type: 'DELETE_RELEASES', ids: [id, id, 'missing'] }, now);
    expect(next.releases).toHaveLength(state.releases.length - 1);
    expect(next.order).not.toContain(id);
    expect(next.activity[0].text).toBe('删除 1 个发布单');
    expect(reduce(next, { type: 'DELETE_RELEASES', ids: [id, 'missing'] }, now)).toBe(next);
    expect(reduce(next, { type: 'DELETE_RELEASES', ids: [] }, now)).toBe(next);
  });

  it('should_count_only_restored_releases_when_items_are_duplicate_or_existing', () => {
    const state = seedState(now);
    const [target, existing] = state.releases;
    const item = { release: target, index: 0 };
    const items = [item, item, { release: existing, index: 1 }];
    const deleted = reduce(state, { type: 'DELETE_RELEASES', ids: [target.id] }, now);
    const restored = reduce(deleted, { type: 'RESTORE_RELEASES', items }, now);
    expect(restored.releases).toEqual(state.releases);
    expect(restored.order).toEqual(state.order);
    expect(restored.activity[0].text).toBe('恢复 1 个发布单');
    expect(reduce(restored, { type: 'RESTORE_RELEASES', items }, now)).toBe(restored);
    expect(reduce(restored, { type: 'RESTORE_RELEASES', items: [] }, now)).toBe(restored);
  });

  const targetChanges: [string, Partial<ReleaseInput>][] = [
    ['appId', { appId: 'mobile' }],
    ['version', { version: '2.0.0' }],
    ['env', { env: 'dev' }],
  ];
  it.each(targetChanges)('should_require_rebuild_when_%s_changes', (_, patch) => {
    const state = seedState(now);
    const snapshot = structuredClone(state);
    const target = state.releases.find((release) => release.status === 'published')!;
    const next = reduce(state, {
      type: 'UPDATE_RELEASE', id: target.id, payload: { ...target, ...patch },
    }, now);
    const updated = next.releases.find((release) => release.id === target.id)!;
    expect(updated).toMatchObject({
      ...patch, status: 'draft', progress: 0, artifacts: [], updatedAt: new Date(now).toISOString(),
    });
    expect(updated.platform).toBe(next.apps.find((app) => app.id === updated.appId)!.platform);
    expect(updated.timeline.slice(0, -1)).toEqual(target.timeline);
    expect(updated.timeline.at(-1)?.action).toContain('重新构建');
    expect(updated.createdAt).toBe(target.createdAt);
    expect(state).toEqual(snapshot);
  });

  const metadataChanges: [string, Partial<ReleaseInput>][] = [
    ['title', { title: '新标题' }],
    ['owner', { owner: '新负责人' }],
    ['notes', { notes: '新备注' }],
    ['priority', { priority: 100 }],
  ];
  it.each(metadataChanges)('should_keep_build_results_when_only_%s_changes', (_, patch) => {
    const state = seedState(now);
    for (const target of state.releases) {
      const next = reduce(state, {
        type: 'UPDATE_RELEASE', id: target.id, payload: { ...target, ...patch },
      }, now);
      const updated = next.releases.find((release) => release.id === target.id)!;
      expect(updated).toMatchObject({
        ...patch, status: target.status, progress: target.progress, artifacts: target.artifacts,
      });
      expect(updated.timeline.at(-1)?.action).toBe('编辑发布单');
    }
  });

  it('should_keep_build_results_when_version_only_has_surrounding_whitespace', () => {
    const state = seedState(now);
    const target = state.releases[0];
    const next = reduce(state, {
      type: 'UPDATE_RELEASE', id: target.id, payload: { ...target, version: ` ${target.version} ` },
    }, now);
    expect(next.releases[0]).toMatchObject({
      version: target.version, status: target.status, progress: target.progress, artifacts: target.artifacts,
    });
  });

  it('should_transition_building_to_testing_when_tick_completes', () => {
    const state = seedState(now);
    const building = state.releases.find((r) => r.status === 'building')!;
    building.progress = 99;
    const next = reduce(state, { type: 'TICK' }, now, () => 0.99);
    const after = next.releases.find((r) => r.id === building.id)!;
    expect(after.status).toBe('testing');
    expect(after.progress).toBe(100);
  });

  it('should_reorder_ids_when_drag_drop_is_dispatched', () => {
    const state = seedState(now);
    const [a, b, c] = state.order;
    const next = reduce(state, { type: 'REORDER', sourceId: a, targetId: c }, now);
    expect(next.order.indexOf(a)).toBe(2);
    expect(next.order.indexOf(b)).toBe(0);
    expect(next.order.indexOf(a)).toBeGreaterThan(next.order.indexOf(c));
    expect(next.order.length).toBe(state.order.length);
    expect(reduce(state, { type: 'REORDER', sourceId: a, targetId: a }, now)).toBe(state);
  });

  it('should_filter_and_sort_releases_when_query_changes', () => {
    const state = seedState(now);
    const filtered = filterReleases(state, {
      search: '',
      platform: 'desktop',
      status: 'all',
      env: 'all',
      sort: 'manual',
    });
    expect(filtered.length).toBeGreaterThan(0);
    expect(filtered.every((r) => r.platform === 'desktop')).toBe(true);
    const sorted = sortReleases(filtered, 'version', state.order);
    expect(sorted.length).toBe(filtered.length);
    const active = filterReleases(state, {
      search: '',
      platform: 'all',
      status: 'active',
      env: 'all',
      sort: 'manual',
    });
    expect(active.every((r) => ['building', 'testing', 'approved'].includes(r.status))).toBe(true);
  });

  it('should_persist_and_reload_state_when_storage_round_trips', () => {
    const storage = memoryStorage();
    const state = seedState(now);
    expect(saveState(storage, state)).toBe(true);
    const loaded = loadState(storage, now);
    expect(loaded).toEqual(state);
    expect(storage.getItem(STORAGE_KEY)).toBeTruthy();
  });

  it('should_save_and_delete_view_when_requested', () => {
    const state = seedState(now);
    const query = { search: 'x', platform: 'web', status: 'all', env: 'prod', sort: 'updated' } as const;
    const saved = reduce(state, { type: 'SAVE_VIEW', name: '我的视图', query }, now);
    const view = saved.savedViews.at(-1)!;
    expect(view.name).toBe('我的视图');
    const deleted = reduce(saved, { type: 'DELETE_VIEW', id: view.id }, now);
    expect(deleted.savedViews.some((v) => v.id === view.id)).toBe(false);
  });

  it('should_reject_save_view_when_query_fails_runtime_validation', () => {
    const state = seedState(now);
    const invalidQuery = { search: 'x', platform: 'web', status: 'all', env: 'prod' }; // 缺 sort
    const next = reduce(state, { type: 'SAVE_VIEW', name: '坏视图', query: invalidQuery }, now);
    expect(next).toBe(state);
    expect(next.savedViews).toHaveLength(state.savedViews.length);
  });

  it('should_reject_update_app_when_patch_contains_unknown_field', () => {
    const state = seedState(now);
    const target = state.apps[0];
    const next = reduce(state, { type: 'UPDATE_APP', id: target.id, patch: { id: 'hacked' } }, now);
    expect(next).toBe(state);
    expect(next.apps[0].id).toBe(target.id);
  });

  it('should_reject_update_app_when_health_not_in_enum', () => {
    const state = seedState(now);
    const target = state.apps[0];
    const next = reduce(state, { type: 'UPDATE_APP', id: target.id, patch: { health: 'perfect' } }, now);
    expect(next).toBe(state);
    expect(next.apps[0].health).toBe(target.health);
  });

  it('should_apply_update_app_when_patch_valid', () => {
    const state = seedState(now);
    const target = state.apps[0];
    const next = reduce(state, { type: 'UPDATE_APP', id: target.id, patch: { name: '改名后', owner: '新负责人' } }, now);
    expect(next).not.toBe(state);
    expect(next.apps[0].name).toBe('改名后');
    expect(next.apps[0].owner).toBe('新负责人');
    expect(next.apps[0].health).toBe(target.health);
  });

  it('should_compute_metrics_when_releases_change', () => {
    const state = seedState(now);
    const m = metrics(state);
    expect(m.total).toBe(state.releases.length);
    const sum =
      m.byStatus.published +
      m.byStatus.failed +
      m.byStatus.draft +
      m.byStatus.building +
      m.byStatus.testing +
      m.byStatus.approved +
      m.byStatus.cancelled;
    expect(sum).toBe(m.total);
  });

  it('should_reset_to_seed_when_reset_is_dispatched', () => {
    const state = seedState(now);
    const changed = reduce(state, { type: 'DELETE_RELEASES', ids: [state.releases[0].id] }, now);
    const reset = reduce(changed, { type: 'RESET' }, now);
    expect(reset.releases.length).toBe(state.releases.length);
  });
});

describe('state boundary', () => {
  type InvalidCase = [string, (state: AppState) => unknown];
  const appFields: [string, unknown][] = [
    ['id', ''], ['name', null], ['platform', 'native'], ['path', 123],
    ['version', 1], ['owner', null], ['health', 'unknown'], ['description', {}],
  ];
  const releaseFields: [string, unknown][] = [
    ['id', ' '], ['title', null], ['appId', null], ['platform', 'native'],
    ['version', 1], ['env', 'staging'], ['status', 'ready'], ['priority', '1'],
    ['priority', NaN], ['priority', Infinity], ['owner', null], ['progress', '0'],
    ['progress', NaN], ['progress', -Infinity], ['notes', []],
    ['artifacts', null], ['artifacts', [null]], ['artifacts', [{ name: 'a.zip', size: 12 }]],
    ['artifacts', [{ name: null, size: '12 MB' }]], ['timeline', null], ['timeline', [null]],
    ['timeline', [{ at: 'invalid', action: '创建', by: 'CI' }]],
    ['timeline', [{ at: new Date(now).toISOString(), action: null, by: 'CI' }]],
    ['timeline', [{ at: new Date(now).toISOString(), action: '创建', by: 1 }]],
    ['createdAt', 'invalid'], ['updatedAt', 123],
  ];
  const settingsFields: [string, unknown][] = [
    ['theme', 'system'], ['density', 'tiny'], ['accent', 'red'], ['motion', 'false'],
    ['theme', null], ['density', undefined],
  ];
  const queryFields: [string, unknown][] = [
    ['search', null], ['platform', 'native'], ['status', 'ready'],
    ['env', 'staging'], ['sort', 'random'],
  ];
  const activityFields: [string, unknown][] = [
    ['id', ''], ['at', 'invalid'], ['text', null], ['tone', 'blue'],
  ];
  const invalidCases: InvalidCase[] = [
    ['null root', () => null],
    ['array root', () => []],
    ['missing apps', (state) => ({ releases: state.releases })],
    ['missing releases', (state) => ({ apps: state.apps })],
    ['null app', (state) => ({ ...state, apps: [null] })],
    ['null release', (state) => ({ ...state, releases: [null] })],
    ['incomplete app', (state) => ({ ...state, apps: [{ id: 'web' }] })],
    ['incomplete release', (state) => ({ ...state, releases: [{ id: 'REL-1' }] })],
    ['sparse releases', (state) => ({ ...state, releases: new Array(1) })],
    ['duplicate apps', (state) => ({ ...state, apps: [...state.apps, state.apps[0]] })],
    ['duplicate releases', (state) => ({ ...state, releases: [...state.releases, state.releases[0]] })],
    ['orphan release', (state) => ({ ...state, apps: [] })],
    ['platform mismatch', (state) => ({
      ...state, releases: [{ ...state.releases[0], platform: 'mobile' }, ...state.releases.slice(1)],
    })],
    ['duplicate order', (state) => ({ ...state, order: [state.order[0], state.order[0]] })],
    ['unknown order id', (state) => ({ ...state, order: ['REL-missing'] })],
    ['non-string order id', (state) => ({ ...state, order: [123] })],
    ['null saved view', (state) => ({ ...state, savedViews: [null] })],
    ['null saved query', (state) => ({ ...state, savedViews: [{ ...state.savedViews[0], query: null }] })],
    ['incomplete saved query', (state) => ({ ...state, savedViews: [{ ...state.savedViews[0], query: {} }] })],
    ['invalid saved view id', (state) => ({ ...state, savedViews: [{ ...state.savedViews[0], id: 1 }] })],
    ['invalid saved view name', (state) => ({ ...state, savedViews: [{ ...state.savedViews[0], name: null }] })],
    ['duplicate saved views', (state) => ({ ...state, savedViews: [state.savedViews[0], state.savedViews[0]] })],
    ['null activity entry', (state) => ({ ...state, activity: [null] })],
    ['duplicate activity', (state) => ({ ...state, activity: [state.activity[0], state.activity[0]] })],
    ['array settings', (state) => ({ ...state, settings: [] })],
    ['infinite version', (state) => ({ ...state, version: Infinity })],
    ['NaN version', (state) => ({ ...state, version: NaN })],
    ...['settings', 'order', 'savedViews', 'activity', 'version'].flatMap((field) =>
      [null, undefined, 'invalid'].map<InvalidCase>((value) => [
        `${field} ${String(value)}`, (state) => ({ ...state, [field]: value }),
      ])),
    ...appFields.map<InvalidCase>(([field, value]) => [
      `app ${field}`, (state) => ({
        ...state, apps: [{ ...state.apps[0], [field]: value }, ...state.apps.slice(1)],
      }),
    ]),
    ...releaseFields.map<InvalidCase>(([field, value]) => [
      `release ${field} ${String(value)}`,
      (state) => ({
        ...state, releases: [{ ...state.releases[0], [field]: value }, ...state.releases.slice(1)],
      }),
    ]),
    ...settingsFields.map<InvalidCase>(([field, value]) => [
      `settings ${field} ${String(value)}`, (state) => ({ ...state, settings: { [field]: value } }),
    ]),
    ...queryFields.map<InvalidCase>(([field, value]) => [
      `query ${field}`, (state) => ({
        ...state,
        savedViews: [{ ...state.savedViews[0], query: { ...state.savedViews[0].query, [field]: value } }],
      }),
    ]),
    ...activityFields.map<InvalidCase>(([field, value]) => [
      `activity ${field}`, (state) => ({ ...state, activity: [{ ...state.activity[0], [field]: value }] }),
    ]),
  ];

  it.each(invalidCases)('should_reject_state_without_success_activity_when_invalid_%s', (_, makeData) => {
    const state = seedState(now);
    const snapshot = structuredClone(state);
    const data = makeData(state);
    expect(parseState(data, now)).toBeNull();
    expect(reduce(state, { type: 'IMPORT', data }, now)).toBe(state);
    expect(state).toEqual(snapshot);
  });

  it('should_fill_defaults_when_valid_partial_state_is_provided', () => {
    const base = seedState(now);
    const data = {
      apps: base.apps,
      releases: [base.releases[0]],
      settings: { density: 'compact', motion: false },
    };
    const expected = {
      ...base, ...data, order: [base.releases[0].id], settings: { ...base.settings, ...data.settings },
    };
    expect(parseState(data, now)).toEqual(expected);
    const storage = memoryStorage();
    storage.setItem(STORAGE_KEY, JSON.stringify(data));
    expect(loadState(storage, now)).toEqual(expected);
    const imported = reduce(base, { type: 'IMPORT', data }, now);
    expect(imported).toEqual({ ...expected, activity: imported.activity });
    expect(imported.activity[0]).toMatchObject({ text: '导入数据成功', tone: 'success' });
    expect(imported.activity.slice(1)).toEqual(expected.activity);
  });

  it('should_preserve_explicit_empty_arrays_without_seed_references_when_parsing', () => {
    const data = { apps: [], releases: [], order: [], savedViews: [], activity: [], settings: {} };
    const base = seedState(now);
    const expected = { ...data, settings: base.settings, version: base.version };
    expect(parseState(data, now)).toEqual(expected);
    const imported = reduce(base, { type: 'IMPORT', data }, now);
    expect(imported.releases).toEqual([]);
    expect(imported.order).toEqual([]);
    expect(imported.savedViews).toEqual([]);
    expect(imported.activity).toHaveLength(1);
    expect(data.activity).toEqual([]);
  });

  it('should_rebuild_empty_order_from_supplied_releases_and_preserve_valid_partial_order', () => {
    const state = seedState(now);
    const releases = [state.releases[2], state.releases[0]];
    expect(parseState({ ...state, releases, order: [] }, now)?.order).toEqual(releases.map((r) => r.id));
    const order = [state.releases[0].id];
    expect(parseState({ ...state, releases, order }, now)?.order).toEqual(order);
  });

  it('should_round_trip_exported_state_without_mutating_input_when_importing', () => {
    const state = reduce(seedState(now), { type: 'CREATE_RELEASE', payload: validInput }, now);
    const data = JSON.parse(JSON.stringify(state));
    expect(parseState(data, now)).toEqual(state);
    const imported = reduce(seedState(now), { type: 'IMPORT', data }, now);
    expect(imported).toEqual({ ...state, activity: imported.activity });
    expect(imported.activity[0]).toMatchObject({ text: '导入数据成功', tone: 'success' });
    expect(imported.activity.slice(1)).toEqual(state.activity);
    expect(data).toEqual(state);
    expect(parseState(JSON.parse(JSON.stringify(imported)), now)).toEqual(imported);
  });

  it.each([
    '{bad json',
    'null',
    JSON.stringify({ apps: [], releases: [], settings: { density: 'tiny' } }),
    JSON.stringify({ apps: [null], releases: [] }),
    JSON.stringify({ apps: [], releases: [null] }),
    JSON.stringify({ apps: [], releases: [], order: ['missing'] }),
  ])('should_fall_back_without_overwriting_storage_when_persisted_data_is_invalid_%s', (raw) => {
    const storage = memoryStorage();
    storage.setItem(STORAGE_KEY, raw);
    const write = vi.spyOn(storage, 'setItem');
    expect(loadState(storage, now)).toEqual(seedState(now));
    expect(storage.getItem(STORAGE_KEY)).toBe(raw);
    expect(write).not.toHaveBeenCalled();
  });

  it('should_fall_back_without_writing_when_storage_read_throws', () => {
    const storage: StorageLike = {
      getItem: () => { throw new Error('read denied'); },
      setItem: vi.fn(),
    };
    expect(loadState(storage, now)).toEqual(seedState(now));
    expect(storage.setItem).not.toHaveBeenCalled();
  });

  it('should_return_false_and_preserve_memory_when_storage_write_throws', () => {
    const state = seedState(now);
    const snapshot = structuredClone(state);
    const storage: StorageLike = {
      getItem: () => null,
      setItem: () => { throw new Error('quota exceeded'); },
    };
    expect(saveState(storage, state)).toBe(false);
    expect(state).toEqual(snapshot);
  });
});