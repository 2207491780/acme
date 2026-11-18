import { useCallback, useEffect, useSyncExternalStore } from 'react';
import type { AdminDictItemView, DictState, UseDictResult } from '@acme/core';
import { ApiError, apiFetch } from './api/client';
import { DICT_CACHE_STORAGE_KEY, mobileStorage } from './utils/storage';

/**
 * 字典消费 hook（REQ-20261114-120110 AMB-4：core useDict 的 RN 镜像实现，语义逐条对齐）：
 * 端点为后端既有 `GET /admin/v1/system/dict-types/{typeId}/items`（按 typeId），无按 dictCode
 * 的直查端点——useDict(dictCode) 内部两步解析：
 *   ① `GET /admin/v1/system/dict-types`（keyword 按代码检索，客户端对 `dictCode` 精确匹配）→ typeId；
 *   ② `GET /admin/v1/system/dict-types/{typeId}/items?page&size`（大页取全量）→ 条目。
 * 传输走 mobile 既有 apiFetch（mobile 会话 + API_BASE）；类型 type-only 复用 core。
 *
 * 缓存两层（模块级，跨组件共享）：`typeIdByCode`（code→typeId 解析映射）与 `cache`（code→items）；
 * refresh()/invalidateDicts() 双层失效（解析映射一并重建）。多组件消费同一 dictCode 时
 * 并发并入同一拉取管道（仅一次网络序列），命中双层缓存后零请求（AC-5 单次拉取缓存共享）。
 *
 * 持久化（契约②：字典缓存入 AsyncStorage）：ready 快照经 mobileStorage 写穿透落盘
 * （DICT_CACHE_STORAGE_KEY）；冷启动由 hydrateDicts()（initMobileStorage 之后调用）灌回双层缓存，
 * 灌入数据经运行时校验，非法整包丢弃（不覆盖内存有效状态）。
 */

/** 字典类型检索页大小（keyword 按 dictCode 精确匹配过滤，单页足够）。 */
const DICT_TYPE_LOOKUP_SIZE = 200;
/** 字典条目大页大小（分页端点一次取全量）。 */
const DICT_ITEMS_PAGE_SIZE = 200;

/** 字典缓存持久化键（与业务态 STORAGE_KEY / 查询态分键存放；登记处见 utils/storage.ts）。 */
export { DICT_CACHE_STORAGE_KEY };

const EMPTY_STATE: DictState = { items: [], status: 'idle', loading: false, error: null, loadedAt: null };

/** 缓存层 1：dictCode → typeId 解析映射（失效与 items 层联动）。 */
const typeIdByCode = new Map<string, string>();
/** 缓存层 2：dictCode → 快照（items；跨组件共享）。 */
const cache = new Map<string, DictState>();
/** 进行中的拉取：并发消费/refresh 合并为一次网络管道。 */
const inflight = new Map<string, Promise<void>>();
const listeners = new Set<() => void>();

const notify = (): void => {
  for (const listener of listeners) {
    listener();
  }
};

const sortBySortOrder = (items: AdminDictItemView[]): AdminDictItemView[] =>
  [...items].sort((a, b) => a.sortOrder - b.sortOrder);

// ---- 持久化（契约②） ----

interface PersistedDictItem {
  items: AdminDictItemView[];
  loadedAt: number;
}

interface PersistedDictCache {
  version: 1;
  typeIdByCode: [string, string][];
  items: Record<string, PersistedDictItem>;
}

/** 条目运行时校验（parse-state 同一精神：持久化数据不得用类型断言信任）。 */
function isDictItem(value: unknown): value is AdminDictItemView {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.id === 'string' &&
    v.id.length > 0 &&
    typeof v.dictTypeId === 'string' &&
    typeof v.itemLabel === 'string' &&
    typeof v.itemValue === 'string' &&
    typeof v.sortOrder === 'number' &&
    Number.isFinite(v.sortOrder)
  );
}

function isPersistedCache(value: unknown): value is PersistedDictCache {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  if (v.version !== 1 || !Array.isArray(v.typeIdByCode)) return false;
  if (!v.typeIdByCode.every((p) => Array.isArray(p) && p.length === 2 && typeof p[0] === 'string' && typeof p[1] === 'string')) {
    return false;
  }
  if (typeof v.items !== 'object' || v.items === null) return false;
  return Object.values(v.items).every(
    (entry) =>
      typeof entry === 'object' &&
      entry !== null &&
      Array.isArray((entry as PersistedDictItem).items) &&
      (entry as PersistedDictItem).items.every(isDictItem) &&
      typeof (entry as PersistedDictItem).loadedAt === 'number' &&
      Number.isFinite((entry as PersistedDictItem).loadedAt),
  );
}

/** ready 快照落盘（写穿透：镜像 + AsyncStorage）；仅持久化 ready 条目。 */
function persistDicts(): void {
  const items: Record<string, PersistedDictItem> = {};
  for (const [code, state] of cache) {
    if (state.status === 'ready') {
      items[code] = { items: state.items, loadedAt: state.loadedAt ?? Date.now() };
    }
  }
  const snapshot: PersistedDictCache = {
    version: 1,
    typeIdByCode: [...typeIdByCode.entries()],
    items,
  };
  mobileStorage.setItem(DICT_CACHE_STORAGE_KEY, JSON.stringify(snapshot));
}

/**
 * 冷启动灌回（须在 initMobileStorage 之后调用，镜像已含持久化键值）：
 * 校验通过则重建双层缓存（ready 态，命中即零请求）；非法整包丢弃，不覆盖内存状态。
 */
export function hydrateDicts(): void {
  try {
    const raw = mobileStorage.getItem(DICT_CACHE_STORAGE_KEY);
    if (!raw) return;
    const parsed: unknown = JSON.parse(raw);
    if (!isPersistedCache(parsed)) return;
    for (const [code, typeId] of parsed.typeIdByCode) {
      typeIdByCode.set(code, typeId);
    }
    for (const [code, entry] of Object.entries(parsed.items)) {
      cache.set(code, {
        items: sortBySortOrder(entry.items),
        status: 'ready',
        loading: false,
        error: null,
        loadedAt: entry.loadedAt,
      });
    }
    notify();
  } catch {
    // 持久化数据损坏：整包丢弃，下次消费重跑两步解析
  }
}

// ---- 拉取管道（语义同 core useDict） ----

/** 两步解析：code→typeId（映射未命中时查 dict-types）→ typeId→items（大页）。
 *  force=true（refresh）时双层失效：解析映射一并作废重解析。 */
async function fetchDict(dictCode: string, force = false): Promise<void> {
  if (force) {
    typeIdByCode.delete(dictCode);
  }
  cache.set(dictCode, { items: [], status: 'loading', loading: true, error: null, loadedAt: null });
  notify();
  const task = (async () => {
    try {
      let typeId = typeIdByCode.get(dictCode);
      if (!typeId) {
        const types = await apiFetch<{ records?: { id: string; dictCode: string }[] }>(
          `/admin/v1/system/dict-types?page=1&size=${DICT_TYPE_LOOKUP_SIZE}&keyword=${encodeURIComponent(dictCode)}`,
        );
        const type = (types.records ?? []).find((t) => t.dictCode === dictCode);
        if (!type) {
          throw new ApiError(404, 'DICT_TYPE_NOT_FOUND', `字典类型 ${dictCode} 不存在`);
        }
        typeId = type.id;
        typeIdByCode.set(dictCode, typeId);
      }
      const raw = await apiFetch<AdminDictItemView[]>(
        `/admin/v1/system/dict-types/${typeId}/items?page=1&size=${DICT_ITEMS_PAGE_SIZE}`,
      );
      // 响应兼容运行时校验：条目端点既有口径为数组；分页信封形状不覆盖原数据
      const items = Array.isArray(raw)
        ? raw
        : ((raw as unknown as { records?: AdminDictItemView[] }).records ?? []);
      cache.set(dictCode, {
        items: sortBySortOrder(items),
        status: 'ready',
        loading: false,
        error: null,
        loadedAt: Date.now(),
      });
      persistDicts();
    } catch (ex: unknown) {
      // 解析失败一并失效映射，避免坏 typeId 被缓存复用
      typeIdByCode.delete(dictCode);
      cache.set(dictCode, {
        items: [],
        status: 'error',
        loading: false,
        error: ex instanceof ApiError ? ex.message : '字典加载失败',
        loadedAt: null,
      });
    } finally {
      inflight.delete(dictCode);
      notify();
    }
  })();
  inflight.set(dictCode, task);
  return task;
}

/** 发起拉取：已在进行中则直接并入（不重复请求）；force=true（refresh）时在管道空闲后强制重取。 */
function requestDict(dictCode: string, force = false): Promise<void> {
  if (!dictCode) {
    return Promise.resolve();
  }
  if (inflight.has(dictCode)) {
    return force ? inflight.get(dictCode)!.then(() => fetchDict(dictCode, true)) : inflight.get(dictCode)!;
  }
  return fetchDict(dictCode, force);
}

/** 字典缓存双层失效：传 dictCode 仅失效该类型，缺省全部清空（含持久化快照）；下次消费重跑两步解析。 */
export function invalidateDicts(dictCode?: string): void {
  if (dictCode) {
    typeIdByCode.delete(dictCode);
    cache.delete(dictCode);
  } else {
    typeIdByCode.clear();
    cache.clear();
  }
  persistDicts();
  notify();
}

/**
 * 字典消费 hook：dictCode 为字典类型代码（如 release_env）。
 * 多组件共享同一 dictCode 时仅一次两步拉取；error 态再次挂载自动重试一次；refresh 强制共享重取。
 */
export function useDict(dictCode: string): UseDictResult {
  const subscribe = useCallback((listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);

  const getSnapshot = useCallback((): DictState => cache.get(dictCode) ?? EMPTY_STATE, [dictCode]);

  // useSyncExternalStore：缓存更新触发重渲染并返回最新快照（条目引用仅在状态流转时替换，稳定）
  const state = useSyncExternalStore(subscribe, getSnapshot);

  useEffect(() => {
    const entry = cache.get(dictCode);
    // 未加载 / 上次失败 → 拉取；ready/loading 的并发挂载自动并入（缓存共享）
    if (!dictCode || entry?.status === 'ready' || entry?.status === 'loading') {
      return;
    }
    void requestDict(dictCode);
  }, [dictCode]);

  const refresh = useCallback(() => {
    void requestDict(dictCode, true);
  }, [dictCode]);

  return { ...state, refresh };
}
