import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { systemApi, type AdminDeptView } from './api/system';

/**
 * 部门树消费 hook（REQ-20261114-120104；照 use-dict 缓存范式）：
 * 端点 `GET /admin/v1/system/depts`（全量树、含禁用态，读路径权限 system:dept:list）。
 * 消费面（角色页自定义部门多选 / 用户页部门筛选与表单选择）共享模块级缓存：
 * 并发挂载并入同一次网络序列，命中缓存后零请求；refresh() 强制共享重取，
 * invalidateDeptTree() 供部门 CRUD 后跨页失效。
 *
 * 快照语义：状态流转时整体替换快照对象（引用稳定），经 useSyncExternalStore 订阅，
 * 所有挂载组件随缓存更新同步渲染。
 */

/** 部门树缓存状态机：loading → ready / error（error 可 refresh 重试）。 */
export type DeptTreeStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface DeptTreeState {
  tree: AdminDeptView[];
  status: DeptTreeStatus;
  /** 便捷读法：status === 'loading'。 */
  loading: boolean;
  error: string | null;
  loadedAt: number | null;
}

export interface UseDeptTreeResult extends DeptTreeState {
  /** 显式刷新：强制重取部门树，结果共享给所有消费者。 */
  refresh: () => void;
}

const EMPTY_STATE: DeptTreeState = { tree: [], status: 'idle', loading: false, error: null, loadedAt: null };

/** 模块级单例缓存（跨组件共享；引用仅在状态流转时替换）。 */
let cache: DeptTreeState = EMPTY_STATE;
/** 进行中的拉取：并发消费/refresh 合并为一次网络管道。 */
let inflight: Promise<void> | null = null;
const listeners = new Set<() => void>();

const notify = (): void => {
  for (const listener of listeners) {
    listener();
  }
};

/** 部门树拉取：响应兼容运行时校验（树数组字面口径；分页信封形状不覆盖原数据，外部输入不信任）。 */
async function fetchDeptTree(force = false): Promise<void> {
  if (force) {
    inflight = null;
  }
  cache = { ...EMPTY_STATE, status: 'loading', loading: true };
  notify();
  const task = (async () => {
    try {
      const raw = await systemApi.deptTree();
      const tree = Array.isArray(raw)
        ? raw
        : ((raw as unknown as { records?: AdminDeptView[] } | null)?.records ?? []);
      cache = { tree, status: 'ready', loading: false, error: null, loadedAt: Date.now() };
    } catch (ex: unknown) {
      cache = {
        tree: [],
        status: 'error',
        loading: false,
        error: ex instanceof Error && ex.message ? ex.message : null,
        loadedAt: null,
      };
    } finally {
      inflight = null;
      notify();
    }
  })();
  inflight = task;
  return task;
}

/** 发起拉取：已在进行中则直接并入（不重复请求）；force=true（refresh）时在管道空闲后强制重取。 */
function requestDeptTree(force = false): Promise<void> {
  if (inflight) {
    return force ? inflight.then(() => fetchDeptTree(true)) : inflight;
  }
  return fetchDeptTree(force);
}

/** 部门树缓存失效：下次消费重新拉取（部门 CRUD 后跨页调用）。 */
export function invalidateDeptTree(): void {
  cache = EMPTY_STATE;
  notify();
}

/**
 * 部门树消费 hook：多组件共享同一次拉取；error 态再次挂载自动重试一次；refresh 强制共享重取。
 */
export function useDeptTree(): UseDeptTreeResult {
  const subscribe = useCallback((listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);

  const getSnapshot = useCallback((): DeptTreeState => cache, []);

  // useSyncExternalStore：缓存更新触发重渲染并返回最新快照（快照引用仅在状态流转时替换，稳定）
  const state = useSyncExternalStore(subscribe, getSnapshot);

  useEffect(() => {
    // 未加载 / 上次失败 → 拉取；ready/loading 的并发挂载自动并入（缓存共享）
    if (state.status === 'ready' || state.status === 'loading') {
      return;
    }
    void requestDeptTree();
  }, [state.status]);

  const refresh = useCallback(() => {
    void requestDeptTree(true);
  }, []);

  return { ...state, refresh };
}
