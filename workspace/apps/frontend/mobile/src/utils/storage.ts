import type { StorageLike } from '@acme/core';

export function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (k) => (map.has(k) ? map.get(k)! : null),
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
  };
}

// 脚手架使用内存态保证可运行；生产持久化接入 AsyncStorage（异步），
// 在启动时 hydrate 到同步缓存，再复用 core 的 loadState/saveState。
export const mobileStorage: StorageLike = memoryStorage();