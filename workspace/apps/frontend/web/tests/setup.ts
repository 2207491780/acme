import { createRequire } from 'node:module';
import { afterAll, vi } from 'vitest';

/**
 * vitest 全局环境桩（node 环境 → 浏览器最小面；DOM 环境 → 消除 AntD cssinjs / 831 图标加载 / Canvas 报错刷屏）：
 * 1. node 环境：web 应用与认证链路触碰 window.localStorage / sessionStorage / location.hash，
 *    以内存实现兜底（零依赖）。
 * 2. DOM 环境（jsdom）：
 *    - 拦截 CJS 侧 @ant-design/icons（含 AntD 内部 154 个直引路径），返回零开销轻量图标组件
 *      （保留精确 .anticon.anticon-<name> 与 <svg data-icon="<name>"> 结构），省 ~2.2s/worker；
 *    - 禁用 @ant-design/cssinjs 运行时样式编译与 <style> 注入，注入 4 条最小 *-hidden 可见性规则；
 *    - 兼容 window.getComputedStyle(elt, pseudoElt)、URL.revokeObjectURL 与 HTMLCanvasElement.getContext；
 *    - afterAll 统一重置假时钟、全局桩、@acme/core 全部全局 store 与 web 单例 store（useTabsStore / useStore）。
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

if (typeof document === 'undefined') {
  vi.stubGlobal('localStorage', localStorageStub);
  vi.stubGlobal('sessionStorage', sessionStorageStub);
  vi.stubGlobal('window', {
    localStorage: localStorageStub,
    sessionStorage: sessionStorageStub,
    location: { hash: '' },
  });
} else {
  // 0) 在 antd 被测试文件导入前拦截 CJS 侧 @ant-design/icons（含 AntD 内部 @ant-design/icons/<Name>）
  try {
    const req = createRequire(import.meta.url);
    const Module = req('node:module') as {
      _load: (request: string, parent: unknown, isMain: boolean) => unknown;
      __acmeAntdIconsHooked?: boolean;
    };
    if (!Module.__acmeAntdIconsHooked) {
      const React = req('react') as typeof import('react');
      const iconCache = new Map<string, unknown>();
      const makeIcon = (rawName: string) => {
        const kebab = rawName
          .replace(/(Outlined|Filled|TwoTone)$/, '')
          .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
          .toLowerCase();
        const Comp = React.forwardRef<HTMLSpanElement, Record<string, unknown>>(function AntdIconStub(props, ref) {
          const {
            className,
            style,
            onClick,
            spin: _spin,
            rotate: _rotate,
            twoToneColor: _twoToneColor,
            component: _component,
            ...rest
          } = props ?? {};
          return React.createElement(
            'span',
            {
              ...rest,
              ref,
              role: 'img',
              'aria-label': kebab,
              className: `anticon anticon-${kebab}${className ? ` ${String(className)}` : ''}`,
              style: style as React.CSSProperties | undefined,
              onClick: onClick as React.MouseEventHandler<HTMLSpanElement> | undefined,
            },
            React.createElement('svg', { 'data-icon': kebab, 'aria-hidden': 'true' }),
          );
        });
        Comp.displayName = rawName;
        return Comp;
      };
      const getIcon = (name: string) => {
        let comp = iconCache.get(name);
        if (!comp) {
          comp = makeIcon(name);
          iconCache.set(name, comp);
        }
        return comp;
      };
      const defaultIcon = makeIcon('icon');
      const iconProxy = new Proxy(
        {
          __esModule: true,
          default: defaultIcon,
          createFromIconfontCN: () => makeIcon('iconfont'),
          getTwoToneColor: () => '#1677ff',
          setTwoToneColor: () => {},
        } as Record<string, unknown>,
        {
          get(target, prop) {
            if (prop in target) return target[prop as string];
            if (typeof prop === 'string' && /^[A-Z]/.test(prop)) return getIcon(prop);
            return undefined;
          },
        },
      );
      const origLoad = Module._load;
      Module._load = function patchedLoad(request: string, parent: unknown, isMain: boolean) {
        if (request === '@ant-design/icons') return iconProxy;
        const m = /^@ant-design\/icons\/(?:(?:lib|es)\/icons\/)?([A-Z][A-Za-z0-9]+)$/.exec(request);
        if (m) return { __esModule: true, default: getIcon(m[1]!) };
        return origLoad.call(this, request, parent, isMain);
      };
      Module.__acmeAntdIconsHooked = true;
    }
  } catch {
    // 非 Node/非 antd 场景忽略
  }

  // 1) 兼容 jsdom 缺失 API：getComputedStyle(elt, pseudoElt)、URL.revokeObjectURL 与 Canvas.getContext
  if (typeof window !== 'undefined' && typeof window.getComputedStyle === 'function') {
    const nativeGetComputedStyle = window.getComputedStyle.bind(window);
    window.getComputedStyle = ((elt: Element, _pseudoElt?: string | null) =>
      nativeGetComputedStyle(elt)) as typeof window.getComputedStyle;
  }
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL !== 'function') {
    URL.revokeObjectURL = () => {};
  }
  if (typeof HTMLCanvasElement !== 'undefined') {
    // 消除 AntD Watermark 在 jsdom 每次挂载触发的 Not implemented: getContext 栈追踪刷屏；
    // 保留可 spyOn 的函数签名，不影响 watermark 测试自行 stub Canvas 2D context。
    Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
      configurable: true,
      writable: true,
      value: () => null,
    });
  }

  afterAll(async () => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL !== 'function') {
      URL.revokeObjectURL = () => {};
    }
    // 跨文件共享 worker 时彻底重置 @acme/core 与 web 全部全局单例 store
    try {
      const [core, { useStore }, { useTabsStore }] = await Promise.all([
        import('@acme/core'),
        import('../src/store/useStore'),
        import('../src/store/tabsStore'),
      ]);
      core.useAuthStore.getState().clear();
      core.useMeStore.getState().clear();
      core.useNotifyStore.setState({ unreadCount: 0 });
      core.useLockStore.getState().stopIdleWatch();
      core.useLockStore.setState({ locked: false, passcode: '' });
      core.useSystemConfigStore.setState({
        watermarkEnabled: true,
        captchaEnabled: false,
        lockIdleTimeoutMs: 0,
        footerCopyright: null,
        footerVisible: false,
        watermarkTemplate: null,
        version: '0',
        loaded: false,
      });
      await core.i18n.changeLanguage('zh-CN');
      core.useDateFormatStore.getState().setTemplate('auto');
      core.useTimezoneStore.getState().setTimezone('system');
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
        state: core.seedState(),
        storageError: false,
        storageConflict: false,
        modal: null,
      });
    } catch {
      // 非 web-dom 环境忽略
    }
  });

  // 2) 跳过 @ant-design/cssinjs 运行时样式生成与海量 <style> 注入，保留 4 条最小可见性规则
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
