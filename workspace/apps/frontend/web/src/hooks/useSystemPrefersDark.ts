import { useEffect, useState } from 'react';

/**
 * 系统深浅色监听（REQ-20261121-010725，AC-4）：prefers-color-scheme 的 matchMedia 解析 + change 监听。
 * mode=system 时由消费方（main.tsx buildTheme / App dataset / themeSnapshot）注入解析结果；
 * core 不引 React，监听副作用归 web 侧 hook（TL 钉死口径）。环境无 matchMedia（node 测试桩）时
 * 安全降级为 false 且不挂监听。
 */
export function useSystemPrefersDark(): boolean {
  const [prefersDark, setPrefersDark] = useState<boolean>(() => readSystemPrefersDark());

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return undefined;
    }
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (event: MediaQueryListEvent) => setPrefersDark(event.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  return prefersDark;
}

function readSystemPrefersDark(): boolean {
  try {
    return typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}
