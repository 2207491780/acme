import { vi } from 'vitest';

/**
 * vitest 全局环境桩（node 环境 → 浏览器最小面）：
 * web 应用与认证链路触碰 window.localStorage / sessionStorage / location.hash，
 * 以内存实现兜底（零依赖；Map 每测试文件独立，跨文件不共享）。
 */
function storageFactory(): Storage {
  const m = new Map<string, string>();
  return {
    getItem: (key: string) => m.get(key) ?? null,
    setItem: (key: string, value: string) => {
      m.set(key, value);
    },
    removeItem: (key: string) => {
      m.delete(key);
    },
    clear: () => m.clear(),
    key: (index: number) => [...m.keys()][index] ?? null,
    get length() {
      return m.size;
    },
  } as Storage;
}

const localStorageStub = storageFactory();
const sessionStorageStub = storageFactory();

// jsdom 环境（*.test.tsx 组件测试）自带真实 window/localStorage/location，不打桩；
// node 环境维持最小桩（store/工具测试）。
if (typeof window === 'undefined') {
  vi.stubGlobal('localStorage', localStorageStub);
  vi.stubGlobal('sessionStorage', sessionStorageStub);
  vi.stubGlobal('window', {
    localStorage: localStorageStub,
    sessionStorage: sessionStorageStub,
    location: { hash: '' },
  });
}
