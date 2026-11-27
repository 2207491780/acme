import { createRequire } from 'node:module';
import { afterAll, vi } from 'vitest';

/**
 * vitest 全局环境桩（node 环境 → 浏览器最小面；jsdom 环境 → 消除 AntD cssinjs/样式计算瓶颈）：
 * 1. node 环境：web 应用与认证链路触碰 window.localStorage / sessionStorage / location.hash，
 *    以内存实现兜底（零依赖；Map 每测试文件独立，跨文件不共享）。
 * 2. jsdom 环境：
 *    - 禁用 @ant-design/cssinjs 运行时样式编译与 <style> 注入（jsdom cssstyle 解析与 getComputedStyle
 *      在海量 AntD 样式表下退化为 O(N×M)，把单条 findByRole 拖至 4~15s）；保留全部 className、
 *      inline style 与 theme.getDesignToken 计算语义不变；
 *    - 兼容 window.getComputedStyle(elt, pseudoElt)：jsdom 25 对第二参数直接报错并刷屏 stderr，
 *      降级委派到单参数 getComputedStyle(elt) 消除异常开销。
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

// jsdom 环境（*.test.tsx 组件测试）自带真实 window/document/getComputedStyle，不打桩；
// node 环境维持最小桩（store/工具测试）。
if (typeof document === 'undefined') {
  vi.stubGlobal('localStorage', localStorageStub);
  vi.stubGlobal('sessionStorage', sessionStorageStub);
  vi.stubGlobal('window', {
    localStorage: localStorageStub,
    sessionStorage: sessionStorageStub,
    location: { hash: '' },
  });
} else {
  // 1) 兼容 jsdom 未实现的 window.getComputedStyle(elt, pseudoElt) 与 URL.revokeObjectURL
  //    （防止 file-transfer.ts 的异步 setTimeout 清理回调在跨文件 unstubAllGlobals 后抛未捕获异常）
  if (typeof window !== 'undefined' && typeof window.getComputedStyle === 'function') {
    const nativeGetComputedStyle = window.getComputedStyle.bind(window);
    window.getComputedStyle = ((elt: Element, _pseudoElt?: string | null) =>
      nativeGetComputedStyle(elt)) as typeof window.getComputedStyle;
  }
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL !== 'function') {
    URL.revokeObjectURL = () => {};
  }

  afterAll(async () => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL !== 'function') {
      URL.revokeObjectURL = () => {};
    }
    // 跨文件共享 worker 时重置 web 级单例 store（含 useStore 闭包 persisted 与 useTabsStore），
    // 防止前一文件的 layoutMode/tabs/preferences 污染后续 AppShell/Nav 测试。
    try {
      const [{ seedState }, { useStore }, { useTabsStore }] = await Promise.all([
        import('@acme/core'),
        import('../src/store/useStore'),
        import('../src/store/tabsStore'),
      ]);
      useTabsStore.getState().clear();
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.clear();
        if (useStore.getState().dispatch({ type: 'RESET' })) {
          window.localStorage.clear();
          useStore.getState().dispatch({ type: 'RESET' });
        }
        window.localStorage.clear();
      }
      useStore.setState({
        state: seedState(),
        storageError: false,
        storageConflict: false,
        modal: null,
      });
    } catch {
      // 非 web-dom 环境忽略
    }
  });

  // 2) 跳过 @ant-design/cssinjs 运行时样式生成与海量 <style> 注入（仅保留返回 node 透传），
  //    并注入最小可见性规则（仅 4 个 *-hidden 类），保障 Popconfirm/Dropdown/Tooltip 关闭态在
  //    Testing Library getByRole/findByRole 下与真实 AntD 样式语义完全一致。
  const ensureHiddenStyle = () => {
    if (typeof document !== 'undefined' && document.head && !document.getElementById('acme-test-antd-hidden')) {
      const style = document.createElement('style');
      style.id = 'acme-test-antd-hidden';
      style.textContent =
        '.ant-popover-hidden,.ant-tooltip-hidden,.ant-dropdown-hidden,.ant-select-dropdown-hidden{display:none!important;}';
      document.head.appendChild(style);
    }
  };
  ensureHiddenStyle();

  try {
    const req = createRequire(import.meta.url);
    const antdReq = createRequire(req.resolve('antd'));
    const noopRegister = () => (node: unknown) => node;
    const styleReg = antdReq('@ant-design/cssinjs/lib/hooks/useStyleRegister') as { default?: unknown };
    if (styleReg && typeof styleReg.default === 'function') {
      styleReg.default = noopRegister;
    }
    try {
      const cssVarReg = antdReq('@ant-design/cssinjs/lib/hooks/useCSSVarRegister') as { default?: unknown };
      if (cssVarReg && typeof cssVarReg.default === 'function') {
        cssVarReg.default = noopRegister;
      }
    } catch {
      // 低版本 cssinjs 无独立 useCSSVarRegister 时忽略
    }
  } catch {
    // 非 antd 场景忽略
  }
}
