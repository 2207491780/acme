import { beforeEach, describe, expect, it, vi } from 'vitest';
import { STORAGE_KEY, parseState, reduce, seedState } from '@acme/core';
import type { AppState, ReleaseInput } from '@acme/core';
import { createMobileStore } from '../src/store/useStore';
import { initMobileStorage, mobileStorage } from '../src/utils/storage';

/**
 * REQ-20261114-120110 AC-3（AsyncStorage 适配器接入层）：
 * `storage.ts` 由内存态替换为 AsyncStorage 实现（TL 裁决 AMB-5 钉死导出面）：
 *   - 保留 `mobileStorage: StorageLike` 同步消费面（useStore.ts / core loadState/saveState 不变）；
 *   - 新增异步初始化入口 `initMobileStorage(): Promise<void>`（App 启动显式 await；
 *     不用模块级 top-level await）；
 *   - 冷启动 hydrate 走 core parse-state 校验管道：非法数据回落种子态、不覆盖内存有效状态。
 * AsyncStorage 断言口径（TL 确认）：
 *   ① 预置合法 state JSON → initMobileStorage → loadState 恢复该状态；
 *   ② 预置非法 JSON → 初始化 → 回落种子态、不崩溃、内存有效状态不被破坏；
 *   ③ dispatch 写入 → AsyncStorage 落盘 STORAGE_KEY。
 * mock 范本：mobile/tests/auth.test.ts（vi.hoisted 内存桩 + vi.mock 原生模块）。
 */

// AsyncStorage 以可读写内存桩替代（原生模块在 Node/vitest 下不可加载）
const asyncMem = vi.hoisted(() => {
  const store = new Map<string, string>();
  const api = {
    getItem: vi.fn(async (key: string) => store.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn(async (key: string) => {
      store.delete(key);
    }),
  };
  return { store, api };
});
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: asyncMem.api,
  AsyncStorage: asyncMem.api,
}));

const now = Date.now();

const validInput: ReleaseInput = {
  title: '移动端 AsyncStorage 恢复',
  appId: 'mobile',
  version: '1.4.0',
  env: 'test',
  owner: '苏然',
  priority: 1,
  notes: '',
};

/** 经 core reducer 产出的合法状态（与运行时写穿 AsyncStorage 的数据同构）。 */
function validPersistedState(): AppState {
  const base = seedState(now);
  return reduce(base, { type: 'CREATE_RELEASE', payload: validInput }, now);
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  asyncMem.store.clear();
});

describe('REQ20261114120110 AsyncStorage 持久化适配器（AC-3）', () => {
  it('should_restore_business_state_on_cold_start_when_async_storage_valid', async () => {
    asyncMem.store.set(STORAGE_KEY, JSON.stringify(validPersistedState()));

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    // 冷启动：AsyncStorage 持久化的业务态（含新增发布单）经 parse-state 管道恢复
    const restored = store.getState().state;
    const created = restored.releases.find((r) => r.title === validInput.title);
    expect(created).toBeTruthy();
    expect(created!.platform).toBe('mobile');
    expect(created!.version).toBe(validInput.version);
    // 恢复的是持久化状态而非种子（种子无该标题发布单）
    expect(seedState(now).releases.some((r) => r.title === validInput.title)).toBe(false);
  });

  it('should_fallback_to_seed_when_async_storage_empty', async () => {
    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    // 首装（AsyncStorage 无数据）：回落种子态
    expect(store.getState().state.releases).toHaveLength(14);
    expect(store.getState().state.apps).toHaveLength(3);
  });

  it('should_fallback_to_seed_when_async_storage_json_broken', async () => {
    asyncMem.store.set(STORAGE_KEY, '{not-json');

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    // 坏 JSON：不崩溃，回落种子态
    expect(store.getState().state.releases).toHaveLength(14);
    expect(parseState(JSON.parse(JSON.stringify(store.getState().state)), now)).not.toBeNull();
  });

  it('should_fallback_to_seed_when_async_storage_field_type_invalid', async () => {
    const corrupted = validPersistedState() as unknown as { releases: Array<Record<string, unknown>> };
    corrupted.releases[0]!.version = 123;
    asyncMem.store.set(STORAGE_KEY, JSON.stringify(corrupted));

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    // 非法数据不覆盖有效状态：整体回落种子态，而非部分采用损坏数据
    expect(store.getState().state.releases).toHaveLength(14);
    expect(
      store.getState().state.releases.some((r) => r.title === validInput.title),
    ).toBe(false);
  });

  it('should_recover_persistence_after_invalid_data_when_user_dispatches', async () => {
    asyncMem.store.set(STORAGE_KEY, '{not-json');

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    // 非法数据回落后，后续业务写入恢复正常并写穿 AsyncStorage（有效状态不被坏数据卡死）
    const ok = store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    expect(ok).toBe(true);
    expect(store.getState().state.releases).toHaveLength(15);
    await flush();

    const persisted = asyncMem.store.get(STORAGE_KEY);
    expect(persisted).not.toBeNull();
    const reparsed = parseState(JSON.parse(persisted!), now);
    expect(reparsed).not.toBeNull();
    expect(reparsed!.releases.some((r) => r.title === validInput.title)).toBe(true);
  });

  it('should_persist_dispatched_state_to_async_storage_key', async () => {
    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    const ok = store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    expect(ok).toBe(true);
    await flush();

    // dispatch 写入 → AsyncStorage 落盘 STORAGE_KEY（acme.frontend.ops.v1）
    const persisted = asyncMem.store.get(STORAGE_KEY);
    expect(persisted).not.toBeNull();
    const parsed: unknown = JSON.parse(persisted!);
    const releases = (parsed as { releases: Array<{ title: string }> }).releases;
    expect(releases.some((r) => r.title === validInput.title)).toBe(true);
  });
});
