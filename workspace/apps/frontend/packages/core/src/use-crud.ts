import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from './http/client';

/**
 * 通用 CRUD headless 状态机（框架无关：纯 React hooks，不依赖任何 UI 库）。
 * 职责：列表分页 / 关键字搜索 / 筛选 / 权限点显隐 / 竞态防护 / 空页回退。
 * 展示层由各端自行渲染（web 为 AntD5 CrudTable/CrudFormModal）。
 *
 * 口径说明（对齐 tester 用例 REQ20261114071607-useCrud）：
 * - 分页结果 records 为主口径，list 为兼容别名（同数组引用）；
 * - 翻页 gotoPage 为主口径，goToPage 为兼容别名；
 * - 查询形状最小化：keyword 非空才携带、任一筛选值非 undefined 才携带 filters；
 * - can(key)：key 命中 actionPermissions 映射 → 校验映射的权限码；否则 key 视为权限码直接校验；
 *   未提供 permissions（含 me 未加载）一律 fail-closed（false）。
 */

/** 动作名（可经 actionPermissions 映射到权限码；超出此集合的 key 按权限码直查）。 */
export type CrudAction = 'create' | 'update' | 'delete';

/** 分页结果最小契约（records 为主口径；list 为 { list, total } 兼容别名；total 为 Long，按数字容错）。 */
export interface CrudPage<T> {
  records?: T[];
  /** 兼容别名（PageResult.list 风格）。 */
  list?: T[];
  total: number;
}

/** 筛选集合形态：键名 → 筛选值（undefined 表示不过滤，请求适配方自行取舍）。 */
export type CrudFilters = Record<string, string | undefined>;

/** useCrud 发起的单次列表查询（fetchPage 适配方把它翻译成具体 API 调用）。
 *  keyword/filters 为可选：空参省略（无关键字、全部筛选未设置时不出现该键）。 */
export interface CrudQuery<F extends CrudFilters = CrudFilters> {
  page: number;
  size: number;
  keyword?: string;
  filters?: F;
}

export type CrudFetch<T, F extends CrudFilters = CrudFilters> = (query: CrudQuery<F>) => Promise<CrudPage<T>>;

/** 列表请求状态机：空闲 → 加载中 → 就绪 / 出错（reload / 翻页 / 搜索均驱动流转）。 */
export type CrudStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface UseCrudOptions<T, F extends CrudFilters = CrudFilters> {
  /** 分页取数函数（调用方适配具体 API；函数身份变化不触发重新拉取）。 */
  fetchPage: CrudFetch<T, F>;
  /** 筛选键初值（如 { status: undefined }）。 */
  filters?: F;
  /** 初始已提交关键字（默认空）。 */
  keyword?: string;
  /** 页大小（默认 10）。 */
  pageSize?: number;
  /** 当前用户权限码集合：传入后 can() 按集合 fail-closed 判定；未传视为无任何权限。 */
  permissions?: string[];
  /** 动作 → 权限码映射（can('create') 等动作语义；未映射的 key 回退为权限码直查）。 */
  actionPermissions?: Partial<Record<CrudAction | string, string>>;
  /** fetchPage 失败且非 ApiError 时的兜底文案（ApiError.message 优先）。 */
  errorMessage?: string;
  /** 挂载即拉取首页（默认 true）。 */
  immediate?: boolean;
}

export interface CrudController<T, F extends CrudFilters = CrudFilters> {
  /** 当前页数据（竞态防护：过期响应丢弃）。 */
  records: T[];
  /** records 兼容别名（同数组引用，{ list, total } 口径）。 */
  list: T[];
  /** 总条数。 */
  total: number;
  /** 当前页（1 起）。 */
  page: number;
  /** 页大小。 */
  size: number;
  /** 已提交关键字。 */
  keyword: string;
  /** 已提交筛选集合。 */
  filters: F;
  /** 状态机：idle/loading/ready/error。 */
  status: CrudStatus;
  /** 是否加载中（status === 'loading' 的便捷读法）。 */
  loading: boolean;
  /** 加载失败文案（null = 无错误）。 */
  error: string | null;
  /**
   * 权限点显隐判定（fail-closed）：key 命中 actionPermissions 映射 → 校验映射权限码；
   * 否则 key 视为权限码直接校验；未提供 permissions 时一律 false。
   */
  can: (key: string) => boolean;
  /** 以当前查询重取；传 targetPage 时先切页（等价「删除后重取当页/新建后回首页」语义）。 */
  reload: (targetPage?: number) => void;
  /** 翻页（按目标页拉取，total 取最新响应）。 */
  gotoPage: (page: number) => void;
  /** gotoPage 兼容别名（同义）。 */
  goToPage: (page: number) => void;
  /** 提交关键字搜索（覆盖旧 keyword 并重置到第 1 页）。 */
  search: (keyword: string) => void;
  /** 提交单个筛选值（重置到第 1 页）。 */
  setFilter: <K extends keyof F & string>(key: K, value: F[K]) => void;
  /** 清空全部筛选（重置到第 1 页）。 */
  resetFilters: () => void;
}

const DEFAULT_PAGE_SIZE = 10;

/**
 * 通用 CRUD 列表状态机 hook。
 * 行为口径与 UsersPage 原实现对齐：过期响应按序号丢弃；翻到空页且仍有数据时回退上一页；
 * 搜索/筛选提交重置到第 1 页；权限显隐 fail-closed。
 */
export function useCrud<T, F extends CrudFilters = CrudFilters>(
  options: UseCrudOptions<T, F>,
): CrudController<T, F> {
  const { fetchPage, errorMessage, immediate = true } = options;
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE;
  const initialFilters = (options.filters ?? {}) as F;

  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState(options.keyword ?? '');
  const [filters, setFilters] = useState<F>(initialFilters);
  const [records, setRecords] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<CrudStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  /** 手动重取计数（reload 同页/查询参数不变时也强制重取）。 */
  const [tick, setTick] = useState(0);

  const permissions = options.permissions;
  const actionPermissions = options.actionPermissions;
  const can = useCallback(
    (key: string) => {
      const mapped = actionPermissions?.[key];
      // 命中动作映射 → 校验映射的权限码；否则 key 视为权限码直查（均 fail-closed）
      return (permissions ?? []).includes(mapped ?? key);
    },
    [permissions, actionPermissions],
  );

  // fetchPage 每次渲染可能是新身份：存 ref，不进入拉取 effect 依赖。
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;
  const errorMessageRef = useRef(errorMessage);
  errorMessageRef.current = errorMessage;
  /** 竞态防护序号：仅最新一次请求允许落状态（review 修复口径）。 */
  const seqRef = useRef(0);
  const pageRef = useRef(page);
  pageRef.current = page;

  useEffect(() => {
    // immediate=false：挂载不拉取，直到显式 reload/search/setFilter（tick>0）
    if (!immediate && tick === 0) {
      return;
    }
    const seq = ++seqRef.current;
    setStatus('loading');
    setError(null);
    // 查询形状最小化：空关键字不携带 keyword，全部筛选未设置不携带 filters
    const query: CrudQuery<F> = { page, size: pageSize };
    if (keyword) {
      query.keyword = keyword;
    }
    if (Object.values(filters).some((value) => value !== undefined)) {
      query.filters = filters;
    }
    fetchRef.current(query)
      .then((result) => {
        if (seq !== seqRef.current) return;
        const rows = result.records ?? result.list ?? [];
        setRecords(rows);
        if (rows.length === 0 && page > 1 && Number(result.total) > 0) {
          // 翻到空页（如删除了末页唯一记录）→ 回退上一页重取
          setPage(page - 1);
          return;
        }
        setTotal(Number(result.total));
        setStatus('ready');
      })
      .catch((ex: unknown) => {
        if (seq !== seqRef.current) return;
        // 与 UsersPage 原实现口径一致：仅 ApiError 透出后端文案，其余走兜底
        setError(ex instanceof ApiError ? ex.message : errorMessageRef.current ?? '列表加载失败');
        setStatus('error');
      });
  }, [page, pageSize, keyword, filters, tick, immediate]);

  const reload = useCallback((targetPage?: number) => {
    if (targetPage !== undefined && targetPage !== pageRef.current) {
      setPage(targetPage); // 翻页由 effect 驱动重取
      return;
    }
    setTick((t) => t + 1);
  }, []);

  const gotoPage = useCallback((next: number) => {
    setPage(next);
  }, []);

  const search = useCallback((value: string) => {
    setKeyword(value);
    setPage(1);
    setTick((t) => t + 1);
  }, []);

  const setFilter = useCallback(<K extends keyof F & string>(key: K, value: F[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }) as F);
    setPage(1);
    setTick((t) => t + 1);
  }, []);

  const resetFilters = useCallback(() => {
    // initialFilters 由调用方以稳定字面量传入，本 hook 不处理其身份变化
    setFilters(initialFilters);
    setPage(1);
    setTick((t) => t + 1);
  }, []);

  return useMemo(() => ({
    records,
    list: records,
    total,
    page,
    size: pageSize,
    keyword,
    filters,
    status,
    loading: status === 'loading',
    error,
    can,
    reload,
    gotoPage,
    goToPage: gotoPage,
    search,
    setFilter,
    resetFilters,
  }), [records, total, page, pageSize, keyword, filters, status, error, can, reload, gotoPage, search, setFilter, resetFilters]);
}
