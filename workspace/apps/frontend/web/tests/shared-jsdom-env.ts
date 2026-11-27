import type { Environment } from 'vitest/environments';
import { builtinEnvironments } from 'vitest/environments';

/**
 * 同 worker 复用的持久化 jsdom 环境（配合 isolate: false 消除 70+ .test.tsx 重复加载 antd/react-dom）：
 *
 * 问题根因：Vitest 默认即使开启 isolate: false 也会在每个测试文件间销毁并新建 JSDOM 实例，
 * 而跨文件复用的 react-dom / @testing-library/react 仍缓存首个 window/document 引用，
 * 导致后续文件渲染到已脱离的 body。
 *
 * 解法：每个 worker 进程仅初始化 1 次内置 jsdom 环境；文件间 teardown 仅清理 DOM、storage、
 * location.hash 与残留事件，不销毁 window/document，从而同时保证：
 * 1) antd (~2.5s) + @ant-design/icons (~2.3s) + react-dom 在同 worker 内只解析 1 次；
 * 2) 每个测试文件始终在干净的 DOM 与存储状态下运行。
 */
let cachedReady = false;

const sharedJsdomEnv: Environment = {
  name: 'shared-jsdom',
  transformMode: 'web',
  async setup(global, options) {
    if (!cachedReady) {
      await builtinEnvironments.jsdom.setup(global, options);
      cachedReady = true;
    }
    // 每个测试文件开始前确保干净初始态（保留 setup 注入的最小隐藏规则样式）
    if (typeof document !== 'undefined') {
      document.body.innerHTML = '';
      for (const child of Array.from(document.head.children)) {
        if (child.id !== 'acme-test-antd-hidden') {
          child.remove();
        }
      }
    }
    if (typeof window !== 'undefined') {
      try {
        window.localStorage?.clear();
        window.sessionStorage?.clear();
        window.location.hash = '';
      } catch {
        // 忽略受限 storage 异常
      }
    }
    return {
      teardown() {
        // 文件结束仅清空 DOM 与存储，保留 window/document 供同 worker 下一文件复用
        if (typeof document !== 'undefined') {
          document.body.innerHTML = '';
          for (const child of Array.from(document.head.children)) {
            if (child.id !== 'acme-test-antd-hidden') {
              child.remove();
            }
          }
        }
        if (typeof window !== 'undefined') {
          try {
            window.localStorage?.clear();
            window.sessionStorage?.clear();
            window.location.hash = '';
          } catch {
            // 忽略
          }
        }
      },
    };
  },
};

export default sharedJsdomEnv;
