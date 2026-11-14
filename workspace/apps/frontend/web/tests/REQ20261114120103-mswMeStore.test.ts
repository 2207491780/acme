/** @vitest-environment jsdom */
/**
 * REQ-20261114-120103 AC-7：既有测试样例迁移为 MSW handler 驱动。
 *
 * 迁移对象：web/tests/meStore.test.ts（me 画像加载——服务端画像成功写入 / 403 清会话回登录 /
 * 降级不阻塞 / 并发单飞，4 用例等价迁移；委派单授权「迁移哪个你选，列明」）。
 * 选择理由：纯 store 逻辑、请求面单一（GET /admin/v1/system/me）、原文件为 fetch stubGlobal
 * 驱动的最小样例——是「handler 覆盖既有核心端点」迁移成本最低、语义对照最直接的目标。
 * 原文件退役/保留由 TL 裁决（A-15）。
 *
 * MSW 接入口径（A-14）：
 *   - msw v2 API（http/HttpResponse + msw/node setupServer；版本未在 project.md 标注，假定 v2）；
 *   - handler 以 '*\/admin/v1/system/me' 通配匹配（兼容相对/绝对请求 URL；注释内不得出现「星号+斜杠」相邻序列，故转义留档）；
 *   - jsdom docblock 提供 location 基座（相对 URL 解析）；onUnhandledRequest: 'error'
 *     钉死「handler 覆盖既有核心端点」——未覆盖请求显式红灯；
 *   - 请求计数经 resolver 内闭包计数（MSW 网络层拦截，替代 fetchMock.mock.calls 口径）。
 *
 * 权限播种归一：meView.permissions = 三段式码（round1 裁决⑤口径；原文件短键 'tenant_list' 废除）。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

const meView = {
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED' as const, builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions: ['system:user:list'],
  menus: [],
};

const meBody = { success: true, code: '0', message: null, data: meView };

// ————— MSW 服务与请求计数（A-14） —————
const state = { meCalls: 0 };

const server = setupServer(
  http.get('*/admin/v1/system/me', async () => {
    state.meCalls += 1;
    return HttpResponse.json(meBody);
  }),
);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

beforeEach(() => {
  state.meCalls = 0;
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: 1001,
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
});

afterEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-7：meStore 4 用例等价迁移（MSW handler 驱动全绿） —————
describe('REQ20261114120103 meStore MSW 迁移（AC-7）', () => {
  it('should_load_me_and_update_loaded_state', async () => {
    await useMeStore.getState().load();
    expect(useMeStore.getState().me?.user.username).toBe('admin');
    expect(useMeStore.getState().me?.roles).toEqual(['super_admin']);
    expect(useMeStore.getState().loaded).toBe(true);
    expect(useMeStore.getState().error).toBeNull();
    expect(state.meCalls).toBe(1);
  });

  it('should_clear_auth_and_redirect_on_403', async () => {
    server.use(
      http.get('*/admin/v1/system/me', async () => HttpResponse.json(
        { success: false, code: 'ACCOUNT_DISABLED', message: '账号已停用' },
        { status: 403 },
      )),
    );
    await useMeStore.getState().load();
    expect(useAuthStore.getState().session).toBeNull();
    expect(window.location.hash).toBe('#/login');
  });

  it('should_degrade_gracefully_on_server_error_without_clearing_session', async () => {
    server.use(
      http.get('*/admin/v1/system/me', async () => HttpResponse.text('oops', { status: 502 })),
    );
    await useMeStore.getState().load();
    expect(useMeStore.getState().me).toBeNull();
    expect(useMeStore.getState().error).toContain('接口无响应体');
    expect(useAuthStore.getState().session).not.toBeNull();
    expect(window.location.hash).not.toBe('#/login');
  });

  it('should_deduplicate_concurrent_loads', async () => {
    // 挂起 30ms 放行：并发 load 单飞（MSW resolver 内延迟，替代原 fetchMock 延迟口径）
    server.use(
      http.get('*/admin/v1/system/me', async () => {
        await new Promise((r) => setTimeout(r, 30));
        state.meCalls += 1;
        return HttpResponse.json(meBody);
      }),
    );
    await Promise.all([useMeStore.getState().load(), useMeStore.getState().load()]);
    expect(state.meCalls).toBe(1);
  });
});
