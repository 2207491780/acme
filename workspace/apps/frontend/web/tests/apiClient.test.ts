import { describe, expect, it, vi } from 'vitest';
import { authApi } from '@acme/core';
import { apiFetch } from '@acme/core';

/** web 端 apiFetch 信封解包（FR-013）：void 端点空体豁免（emptyAsSuccess）与默认空体拒绝。 */
describe('web apiFetch', () => {
  it('should_resolve_logout_when_void_endpoint_returns_empty_2xx', async () => {
    // 后端 void 端点按 Modules.md 3.1.1 豁免不出信封（HTTP 200 空体=成功）
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
    await expect(authApi.logout()).resolves.toBeUndefined();
  });

  it('should_reject_empty_body_when_not_marked_empty_as_success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
    await expect(apiFetch<unknown>('/admin/v1/auth/unknown')).rejects.toMatchObject({ code: 'EMPTY_BODY' });
  });

  it('should_reject_empty_body_when_error_status_even_with_empty_as_success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })));
    await expect(authApi.logout()).rejects.toMatchObject({ code: 'EMPTY_BODY' });
  });
});
