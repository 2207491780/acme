/** @vitest-environment jsdom */
/**
 * REQ-20261119-051119 AC-4：按钮级权限原语——useHasPermission 三态 + Auth hide/disable 回归。
 *
 * 权限源 = useMeStore（me.permissions）；未加载 fail-closed（false）；
 * 数组 code 任一命中即通过。Auth hide（缺省）不渲染 / disable 透传 disabled。
 * 环境口径（core 缺省 node 环境按文件声明 jsdom；esbuild classic jsx 显式引入 React 命名空间）。
 */
import * as React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, renderHook, screen } from '@testing-library/react';
import { Auth, useHasPermission, useMeStore, type AdminMeView } from '../src';

/** me 画像播种（AdminMeView wire 口径最小形状）。 */
const meView = (permissions: string[]): AdminMeView => ({
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions,
  menus: [],
  deptName: null,
});

const resetMe = () => useMeStore.setState({ me: null, loading: false, loaded: false, error: null });

/** 接受 disabled 透传的测试替身按钮（模拟 antd Button 的最小行为面）。 */
const TestButton = (props: { disabled?: boolean }) => (
  <button type="button" disabled={props.disabled}>目标按钮</button>
);

afterEach(() => {
  cleanup();
  resetMe();
});

describe('REQ20261119051119 useHasPermission 判定口径（AC-4）', () => {
  it('should_return_false_when_me_not_loaded', () => {
    // me 未加载（fail-closed）：任何码一律 false
    const { result } = renderHook(() => useHasPermission('system:user:create'));
    expect(result.current).toBe(false);
  });

  it('should_return_true_when_code_held_and_false_when_missing', () => {
    useMeStore.setState({ me: meView(['system:user:list', 'system:user:create']) });

    const held = renderHook(() => useHasPermission('system:user:create'));
    expect(held.result.current).toBe(true);

    const missing = renderHook(() => useHasPermission('system:user:delete'));
    expect(missing.result.current).toBe(false);
  });

  it('should_return_true_when_any_code_in_array_matches', () => {
    useMeStore.setState({ me: meView(['system:user:list']) });

    // 数组任一命中
    const anyMatch = renderHook(() => useHasPermission(['system:user:create', 'system:user:list']));
    expect(anyMatch.result.current).toBe(true);

    const noneMatch = renderHook(() => useHasPermission(['system:user:create', 'system:user:delete']));
    expect(noneMatch.result.current).toBe(false);
  });

  it('should_return_false_when_code_omitted', () => {
    useMeStore.setState({ me: meView(['system:user:list']) });
    // 未传 code：无可判定授权语义 → fail-closed
    const { result } = renderHook(() => useHasPermission());
    expect(result.current).toBe(false);
  });
});

describe('REQ20261119051119 Auth 组件（AC-4）', () => {
  it('should_render_children_when_permission_held_in_hide_mode', () => {
    useMeStore.setState({ me: meView(['system:user:create']) });
    render(
      <Auth code="system:user:create">
        <TestButton />
      </Auth>,
    );
    expect(screen.getByRole('button', { name: '目标按钮' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '目标按钮' }).disabled).toBe(false);
  });

  it('should_not_render_children_when_permission_missing_in_hide_mode', () => {
    useMeStore.setState({ me: meView(['system:user:list']) });
    render(
      <Auth code="system:user:create">
        <TestButton />
      </Auth>,
    );
    expect(screen.queryByRole('button', { name: '目标按钮' })).toBeNull();
  });

  it('should_disable_child_when_permission_missing_in_disable_mode', () => {
    useMeStore.setState({ me: meView(['system:user:list']) });
    render(
      <Auth code="system:user:create" mode="disable">
        <TestButton />
      </Auth>,
    );
    // disable：子元素保留在 DOM 且携带 disabled 语义
    const button = screen.getByRole('button', { name: '目标按钮' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('should_render_children_untouched_when_permission_held_in_disable_mode', () => {
    useMeStore.setState({ me: meView(['system:user:create']) });
    render(
      <Auth code="system:user:create" mode="disable">
        <TestButton />
      </Auth>,
    );
    expect((screen.getByRole('button', { name: '目标按钮' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('should_wrap_non_element_children_in_disabled_container_in_disable_mode', () => {
    useMeStore.setState({ me: meView([]) });
    render(
      <Auth code="system:user:create" mode="disable">
        <span>纯文本子节点</span>
        <span>第二节点</span>
      </Auth>,
    );
    // 多节点 children：原生 disabled 容器兜底（后代表单控件一并禁用语义）
    const fieldset = document.querySelector('fieldset[disabled]');
    expect(fieldset).toBeTruthy();
    expect(screen.getByText('纯文本子节点')).toBeTruthy();
    expect(screen.getByText('第二节点')).toBeTruthy();
  });

  it('should_pass_when_any_array_code_matches', () => {
    useMeStore.setState({ me: meView(['system:dict:update']) });
    render(
      <Auth code={['system:dict:create', 'system:dict:update']}>
        <TestButton />
      </Auth>,
    );
    expect(screen.getByRole('button', { name: '目标按钮' })).toBeTruthy();
  });
});
