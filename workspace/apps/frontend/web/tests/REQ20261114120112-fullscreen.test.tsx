/**
 * REQ-20261114-120112 AC-5：全屏工具——进入/退出（Fullscreen API）、Esc 退出、
 * fullscreenchange 状态跟踪（Fullscreen API mock）。
 *
 * 装配口径：hook 级直接测（避免假设 renderHook 可用 → 探针组件）；Fullscreen API 在 jsdom
 * 缺失 → beforeEach 以可配置 property 挂桩（requestFullscreen/exitFullscreen/fullscreenElement），
 * afterEach 还原原生描述符（若无原生则删除），防跨文件脏面。
 *
 * 【表面假定（REQ20261114120112 套件内连续编号）】
 *   A-21 web/src/hooks/useFullscreen 命名导出 useFullscreen()（web/src/hooks 目录惯例），
 *        默认目标=document.documentElement；返回 { isFullscreen: boolean; enter(): void;
 *        exit(): void }。
 *   A-22 状态跟踪：hook 监听 document 的 fullscreenchange 事件刷新 isFullscreen
 *        （进入/退出的确认以事件为准，API Promise 仅触发面）。
 *   A-23 Esc 退出：hook 在 fullscreen 态监听 document keydown，key='Escape' → 调
 *        exitFullscreen；非 Esc 键不触发。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { useFullscreen } from '../src/hooks/useFullscreen';

vi.setConfig({ testTimeout: 15000 });

type FsApi = { isFullscreen: boolean; enter: () => void; exit: () => void };

let latest: FsApi | undefined;
function FsProbe() {
  latest = useFullscreen() as unknown as FsApi;
  return null;
}

const probe = () => {
  render(<FsProbe />);
  expect(latest).toBeDefined();
  return () => latest as FsApi;
};

// ————— Fullscreen API mock（可配置挂桩 + 原生描述符还原） —————
let fullscreenElement: Element | null = null;
let restoreFns: Array<() => void> = [];

const fireFsChange = () => document.dispatchEvent(new Event('fullscreenchange'));

const override = (obj: object, key: string, value: unknown) => {
  const had = Object.getOwnPropertyDescriptor(obj, key);
  Object.defineProperty(obj, key, { configurable: true, writable: true, value });
  restoreFns.push(() => {
    if (had) Object.defineProperty(obj, key, had);
    else delete (obj as Record<string, unknown>)[key];
  });
};

/** getter 挂桩（fullscreenElement 为只读访问器，不能用 value 描述符）。 */
const overrideGetter = (obj: object, key: string, get: () => unknown) => {
  const had = Object.getOwnPropertyDescriptor(obj, key);
  Object.defineProperty(obj, key, { configurable: true, get });
  restoreFns.push(() => {
    if (had) Object.defineProperty(obj, key, had);
    else delete (obj as Record<string, unknown>)[key];
  });
};

const stubFullscreenApi = () => {
  fullscreenElement = null;
  overrideGetter(document, 'fullscreenElement', () => fullscreenElement);
  override(document, 'exitFullscreen', vi.fn(async () => {
    fullscreenElement = null;
    fireFsChange();
  }));
  override(document.documentElement, 'requestFullscreen', vi.fn(async function (this: Element) {
    fullscreenElement = this;
    fireFsChange();
  }));
};

beforeEach(() => {
  restoreFns = [];
  stubFullscreenApi();
});

afterEach(() => {
  cleanup();
  for (const fn of restoreFns.reverse()) fn();
  restoreFns = [];
  vi.clearAllMocks();
  fullscreenElement = null;
});

const exitFullscreenMock = () => (document as unknown as { exitFullscreen: () => Promise<void> }).exitFullscreen as unknown as ReturnType<typeof vi.fn>;
const requestFullscreenMock = () =>
  (document.documentElement as unknown as { requestFullscreen: () => Promise<void> }).requestFullscreen as unknown as ReturnType<typeof vi.fn>;

// ————— AC-5 —————
describe('REQ20261114120112 全屏工具（AC-5）', () => {
  it('should_request_fullscreen_on_root_when_enter_called', async () => {
    const api = probe();
    act(() => { api().enter(); });
    expect(requestFullscreenMock()).toHaveBeenCalled(); // 当前内容区（根元素）申请全屏
    await waitForIsFullscreen(true);
  });

  it('should_exit_fullscreen_when_exit_called', async () => {
    const api = probe();
    act(() => { api().enter(); });
    await waitForIsFullscreen(true);
    act(() => { api().exit(); });
    expect(exitFullscreenMock()).toHaveBeenCalled();
    await waitForIsFullscreen(false);
  });

  it('should_exit_fullscreen_when_escape_pressed', async () => {
    const api = probe();
    act(() => { api().enter(); });
    await waitForIsFullscreen(true);
    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(exitFullscreenMock()).toHaveBeenCalled(); // Esc 退出正常
    await waitForIsFullscreen(false);
  });

  it('should_not_exit_when_non_escape_key_pressed', async () => {
    const api = probe();
    act(() => { api().enter(); });
    await waitForIsFullscreen(true);
    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });
    expect(exitFullscreenMock()).not.toHaveBeenCalled(); // 非 Esc 不误退（控制组）
  });

  it('should_track_state_when_fullscreenchange_fired', async () => {
    const api = probe();
    expect(api().isFullscreen).toBe(false);
    act(() => { fullscreenElement = document.documentElement; fireFsChange(); }); // 外部进入全屏
    await waitForIsFullscreen(true);
    act(() => { fullscreenElement = null; fireFsChange(); }); // 外部退出全屏
    await waitForIsFullscreen(false);
  });
});

/** 轮询 hook 的 isFullscreen（事件驱动刷新，A-22）。 */
async function waitForIsFullscreen(expected: boolean) {
  await waitFor(() => {
    expect((latest as FsApi).isFullscreen).toBe(expected);
  });
}
