import { useCallback, useEffect, useState } from 'react';

/**
 * 全屏工具 hook（REQ-20261114-120112，AC-5）：
 * - 默认目标 document.documentElement，返回 { isFullscreen, enter(), exit() }；
 * - 进入/退出的确认以 document fullscreenchange 事件为准（API Promise 仅触发面）；
 * - fullscreen 态监听 keydown，Escape → exitFullscreen（其余键不误退）。
 */
export function useFullscreen(): {
  isFullscreen: boolean;
  enter: () => void;
  exit: () => void;
} {
  const [isFullscreen, setIsFullscreen] = useState<boolean>(() =>
    Boolean(document.fullscreenElement),
  );

  useEffect(() => {
    const onChange = () => setIsFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  useEffect(() => {
    if (!isFullscreen) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        void document.exitFullscreen?.();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isFullscreen]);

  const enter = useCallback(() => {
    if (!document.fullscreenElement) {
      void document.documentElement.requestFullscreen?.();
    }
  }, []);

  const exit = useCallback(() => {
    if (document.fullscreenElement) {
      void document.exitFullscreen?.();
    }
  }, []);

  return { isFullscreen, enter, exit };
}
