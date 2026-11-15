import { beforeEach, describe, expect, it } from 'vitest';
import {
  STORAGE_KEY,
  parseState,
  reduce,
  seedState,
} from '@acme/core';
import type { AppState, ReleaseInput, StorageLike } from '@acme/core';
import { createMobileStore } from '../src/store/useStore';
import { memoryStorage } from '../src/utils/storage';

/**
 * REQ-20261114-120110 AC-3（parse-state 校验管道层）：
 * AsyncStorage 落地后冷启动恢复仍走 core `loadState → parseState` 运行时校验管道
 * （本层以 StorageLike 抽象断言管道语义，AsyncStorage 适配器接入层用例见
 * REQ20261114120110-storage.test.ts）。断言口径=契约 AC-3：
 *   ① given 持久化合法业务态 when 冷启动 then 状态恢复；
 *   ② given 持久化数据非法 when 冷启动 then 回落种子态、不崩溃，且后续写入恢复正常
 *      （数据非法时不得污染/覆盖有效状态）。
 * 范本：mobile/tests/store.test.ts（memoryStorage 注入 + createMobileStore）。
 */

const now = Date.now();

const validInput: ReleaseInput = {
  title: '移动端持久化恢复',
  appId: 'mobile',
  version: '1.4.0',
  env: 'test',
  owner: '苏然',
  priority: 1,
  notes: '',
};

/** 经 core reducer 产出的合法状态（与运行时写入存储的数据同构）。 */
function validPersistedState(): AppState {
  const base = seedState(now);
  return reduce(base, { type: 'CREATE_RELEASE', payload: validInput }, now);
}

function storeOn(rawStorage: StorageLike) {
  return createMobileStore(rawStorage);
}

function seedRaw(storage: StorageLike, raw: string): void {
  storage.setItem(STORAGE_KEY, raw);
}

beforeEach(() => {
  // 每用例独立存储与时间基准，避免跨用例串扰
});

describe('REQ20261114120110 冷启动恢复 parse-state 校验管道（AC-3）', () => {
  it('should_restore_persisted_state_on_cold_start_when_persisted_data_valid', () => {
    const storage = memoryStorage();
    seedRaw(storage, JSON.stringify(validPersistedState()));

    const store = storeOn(storage);

    // 冷启动：持久化的业务态（含新增发布单）原样恢复
    const restored = store.getState().state;
    const created = restored.releases.find((r) => r.title === validInput.title);
    expect(created).toBeTruthy();
    expect(created!.platform).toBe('mobile');
    expect(created!.version).toBe(validInput.version);
    // 恢复的是完整状态而非种子（种子无该标题发布单）
    expect(seedState(now).releases.some((r) => r.title === validInput.title)).toBe(false);
  });

  it('should_fallback_to_seed_when_persisted_json_broken', () => {
    const storage = memoryStorage();
    seedRaw(storage, '{not-json');

    const store = storeOn(storage);

    // 坏 JSON：不崩溃，回落种子态（14 条种子发布单）
    expect(store.getState().state.releases).toHaveLength(14);
    expect(parseState(JSON.parse(JSON.stringify(store.getState().state)), now)).not.toBeNull();
  });

  it('should_fallback_to_seed_when_persisted_field_type_invalid', () => {
    const storage = memoryStorage();
    const corrupted = validPersistedState() as unknown as { releases: Array<Record<string, unknown>> };
    // release.version 字段类型非法（数字而非字符串）→ parseState 拒绝
    corrupted.releases[0]!.version = 123;
    seedRaw(storage, JSON.stringify(corrupted));

    const store = storeOn(storage);

    // 非法数据不覆盖有效状态：整体回落种子态，而不是部分采用损坏数据
    expect(store.getState().state.releases).toHaveLength(14);
    expect(
      store.getState().state.releases.some((r) => r.title === validInput.title),
    ).toBe(false);
  });

  it('should_fallback_to_seed_when_persisted_settings_enum_invalid', () => {
    const storage = memoryStorage();
    const corrupted = validPersistedState() as unknown as { settings: Record<string, unknown> };
    // settings.theme 非法枚举（parse-state isSettings 校验）
    corrupted.settings.theme = 'neon';
    seedRaw(storage, JSON.stringify(corrupted));

    const store = storeOn(storage);

    expect(store.getState().state.releases).toHaveLength(14);
    expect(store.getState().state.settings.theme).toBe(seedState(now).settings.theme);
  });

  it('should_fallback_to_seed_when_order_references_unknown_release', () => {
    const storage = memoryStorage();
    const corrupted = validPersistedState() as unknown as { order: string[] };
    // order 引用不存在的发布单 id（parse-state 唯一性/引用校验）
    corrupted.order = ['release-not-exist'];
    seedRaw(storage, JSON.stringify(corrupted));

    const store = storeOn(storage);

    expect(store.getState().state.releases).toHaveLength(14);
  });

  it('should_recover_persistence_after_invalid_data_when_user_dispatches', () => {
    const storage = memoryStorage();
    seedRaw(storage, '{not-json');

    const store = storeOn(storage);
    // 非法数据回落后，后续业务写入恢复正常持久化（有效状态不被坏数据卡死）
    const ok = store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    expect(ok).toBe(true);
    expect(store.getState().state.releases).toHaveLength(15);

    const persisted = storage.getItem(STORAGE_KEY);
    expect(persisted).not.toBeNull();
    const reparsed = parseState(JSON.parse(persisted!), now);
    expect(reparsed).not.toBeNull();
    expect(reparsed!.releases.some((r) => r.title === validInput.title)).toBe(true);
  });

  it('should_return_null_from_parse_state_when_release_platform_mismatches_app', () => {
    // parse-state 运行时校验契约直证：release.platform 与所属 app.platform 不一致 → 拒绝
    const corrupted = validPersistedState() as unknown as { releases: Array<Record<string, unknown>> };
    corrupted.releases[0]!.platform = 'web';
    expect(parseState(corrupted, now)).toBeNull();
  });
});
