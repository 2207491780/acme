import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { ApiError } from './http/client';
import { systemApi, type AdminDictItemView } from './api/system';

/**
 * 字典消费 hook（REQ-20261114-120101 前端收敛一期；契约确认记录⑥ round1 裁决）：
 * 端点为后端既有 `GET /admin/v1/system/dict-types/{typeId}/items`（按 typeId），无按 dictCode
 * 的直查端点——useDict(dictCode) 内部两步解析：
 *   ① `GET /admin/v1/system/dict-types`（复用 systemApi.dictTypeList，keyword 按代码检索，
 *      客户端对 `dictCode` 精确匹配）→ 取 typeId；
 *   ② `GET /admin/v1/system/dict-types/{typeId}/items?page&size`（大页取全量）→ 条目。
 * 两步均为读路径，权限 `system:dict:list` 不变。
 *
 * 缓存两层（模块级，跨组件共享）：`typeIdByCode`（code→typeId 解析映射）与 `cache`（code→items）；
 * refresh()/invalidateDicts() 双层失效（解析映射一并重建）。多组件消费同一 dictCode 时
 * 并发并入同一拉取管道（仅一次网络序列），命中双层缓存后零请求。
 *
 * 快照语义：cache 条目对象只在状态流转时整体替换（引用稳定），经 useSyncExternalStore
 * 订阅，所有挂载组件随缓存更新同步渲染。
 */

/** 字典缓存条目状态机：loading → ready / error（error 可 refresh 重试）。 */
export type DictStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface DictState {
  items: AdminDictItemView[];
  status: DictStatus;
  /** 便捷读法：status === 'loading'。 */
  loading: boolean;
  error: string | null;
  loadedAt: number | null;
}

export interface UseDictResult extends DictState {
  /** 显式刷新：双层失效后重跑两步解析（code→typeId→items），结果共享给所有消费者。 */
  refresh: () => void;
}

/** 字典类型检索页大小（keyword 按 dictCode 精确匹配过滤，单页足够）。 */
const DICT_TYPE_LOOKUP_SIZE = 200;
/** 字典条目大页大小（分页端点一次取全量）。 */
const DICT_ITEMS_PAGE_SIZE = 200;

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

/** 两步解析：code→typeId（映射未命中时查 dict-types）→ typeId→items（大页）。
 *  force=true（refresh）时双层失效：解析映射一并作废重解析（契约确认记录⑥）。 */
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
        const types = await systemApi.dictTypeList(1, DICT_TYPE_LOOKUP_SIZE, dictCode);
        const type = (types.records ?? []).find((t) => t.dictCode === dictCode);
        if (!type) {
          throw new ApiError(404, 'DICT_TYPE_NOT_FOUND', `字典类型 ${dictCode} 不存在`);
        }
        typeId = type.id;
        typeIdByCode.set(dictCode, typeId);
      }
      const raw = await systemApi.dictItemList(typeId, 1, DICT_ITEMS_PAGE_SIZE);
      // 响应兼容运行时校验：条目端点既有口径为数组；分页信封形状不覆盖原数据
      const items = Array.isArray(raw)
        ? raw
        : ((raw as unknown as { records?: AdminDictItemView[] }).records ?? []);
      cache.set(dictCode, { items: sortBySortOrder(items), status: 'ready', loading: false, error: null, loadedAt: Date.now() });
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

/** 字典缓存双层失效：传 dictCode 仅失效该类型，缺省全部清空；下次消费重跑两步解析。 */
export function invalidateDicts(dictCode?: string): void {
  if (dictCode) {
    typeIdByCode.delete(dictCode);
    cache.delete(dictCode);
  } else {
    typeIdByCode.clear();
    cache.clear();
  }
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
