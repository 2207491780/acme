import AsyncStorage from '@react-native-async-storage/async-storage';
import { STORAGE_KEY } from '@acme/core';
import type { StorageLike } from '@acme/core';

/** 业务态各持久化键（唯一登记处）：业务态（core parse-state 管道）/ 查询筛选 / 字典缓存。 */
export const QUERY_STORAGE_KEY = 'acme.mobile.query.v1';
export const DICT_CACHE_STORAGE_KEY = 'acme.mobile.dicts.v1';

/** 冷启动灌入键集：所有持久化键均为编译期常量，按已知键全量读取（不依赖 getAllKeys）。 */
const PERSISTED_KEYS: readonly string[] = [STORAGE_KEY, QUERY_STORAGE_KEY, DICT_CACHE_STORAGE_KEY];

/** 既有内存态实现（测试与降级兜底继续使用；生产链路见 mobileStorage）。 */
export function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (k) => (map.has(k) ? map.get(k)! : null),
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
  };
}

/**
 * 异步后备存储面（REQ-20261114-120110 契约②）：与 AsyncStorage / 测试内存桩同型；
 * 冷启动 hydrate 按已知持久化键逐键读取（PERSISTED_KEYS），不依赖 getAllKeys。
 * 会话态不走此面（仍由 expo-secure-store 加密保管）。
 */
export interface AsyncBacking {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

/**
 * 业务态持久化存储（生产链路）：RN 无同步存储，AsyncStorage 为异步 API，而 core 的
 * loadState/saveState 是同步管道。采用「同步镜像 + 异步写穿透」：
 * - 读：命中内存镜像（冷启动由 hydrateMobileStorage 全量灌入）；
 * - 写：先落镜像再异步穿透到后备存储，写失败仅保留内存态（不阻塞 UI、不破坏状态）；
 * - 移除：镜像与后备存储同步删除（后备失败容忍，下次 hydrate 自然收敛）。
 * 内容合法性由 core 的 parse-state 运行时校验管道负责（loadState 内 JSON.parse + parseState），
 * 本适配器只做字节搬运，不解释数据。
 */
const mirror = new Map<string, string>();
// 后备存储缺省即 AsyncStorage（node/vitest 经 vi.mock 内存桩拦截；RN 经 .native.js 原生实现），
// 写穿透不依赖 initMobileStorage 是否已执行（init 仅负责冷启动灌回镜像）。
let backing: AsyncBacking = AsyncStorage;

export const mobileStorage: StorageLike = {
  getItem: (k) => (mirror.has(k) ? mirror.get(k)! : null),
  setItem: (k, v) => {
    mirror.set(k, String(v));
    if (backing) {
      void backing.setItem(k, String(v)).catch(() => undefined);
    }
  },
  removeItem: (k) => {
    mirror.delete(k);
    if (backing) {
      void backing.removeItem(k).catch(() => undefined);
    }
  },
};

/**
 * 冷启动 hydrate（REQ-20261114-120110 AC-3）：接入后备存储并按已知持久化键全量读入同步镜像；
 * 语义为冷启动初始化——镜像整体重灌（先清空再填充），必须在首屏渲染（store 首次 loadState）
 * 前 await 完成。后备存储不可用时按无持久化处理（镜像清空后保持现状，降级为内存态），
 * 与 web 端 storageError 降级口径一致。
 */
export async function hydrateMobileStorage(impl: AsyncBacking): Promise<void> {
  backing = impl;
  try {
    const pairs = await Promise.all(
      PERSISTED_KEYS.map(async (k) => [k, await impl.getItem(k)] as const),
    );
    mirror.clear();
    for (const [k, v] of pairs) {
      if (v != null) {
        mirror.set(k, v);
      }
    }
  } catch {
    // 后备存储不可用：仅保留内存态
  }
}

/**
 * 业务态持久化初始化入口（REQ-20261114-120110 AMB-5 钉死导出面）：
 * App 启动（app 入口）显式 await，冷启动把 AsyncStorage 全量灌入同步镜像；
 * 之后 useStore 的 reloadState 走 core loadState（parse-state 运行时校验管道）：
 * 合法持久化数据恢复、非法数据回落种子态、不覆盖内存有效状态。
 * 不用模块级 top-level await（初始化时点由 App 门禁控制）。
 */
export async function initMobileStorage(): Promise<void> {
  await hydrateMobileStorage(AsyncStorage);
}
