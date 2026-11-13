import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isExpiringSoon, load, useAuthStore } from '../src/store/authStore';

/** 会话存储与续约判定（login-state / login-renew）。 */
describe('authStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  const session = (expiresAtMs = Date.now() + 1_800_000) => ({
    accessToken: 'token-1',
    expiresAtMs,
    userId: 1,
    username: 'admin',
    nickname: '管理员',
    tenantCode: 'default',
  });

  it('should_persist_to_localStorage_when_remember', () => {
    useAuthStore.getState().setSession(session(), true);
    expect(useAuthStore.getState().session).not.toBeNull();
    expect(window.localStorage.getItem('acme.auth.session.v1')).toBeTruthy();
    expect(window.localStorage.getItem('acme.auth.remember.v1')).toBe('1');
  });

  it('should_persist_to_sessionStorage_when_not_remember', () => {
    useAuthStore.getState().setSession(session(), false);
    expect(window.sessionStorage.getItem('acme.auth.session.v1')).toBeTruthy();
    expect(window.localStorage.getItem('acme.auth.session.v1')).toBeNull();
  });

  it('should_only_replace_token_and_expiry_on_renew', () => {
    useAuthStore.getState().setSession(session(), false);
    const before = useAuthStore.getState().session;
    useAuthStore.getState().renewed('token-2', new Date(Date.now() + 1_800_000).toISOString());
    const after = useAuthStore.getState().session;
    expect(after?.accessToken).toBe('token-2');
    expect(after?.userId).toBe(before?.userId);
    expect(after?.tenantCode).toBe(before?.tenantCode);
    expect(after?.expiresAtMs).toBeGreaterThan(Date.now());
  });

  it('should_clear_session_from_both_storages', () => {
    useAuthStore.getState().setSession(session(), true);
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().session).toBeNull();
    expect(window.localStorage.getItem('acme.auth.session.v1')).toBeNull();
    expect(window.sessionStorage.getItem('acme.auth.session.v1')).toBeNull();
  });

  it('should_report_expiring_soon_within_margin', () => {
    const soon = session(Date.now() + 60_000);
    const later = session(Date.now() + 1_800_000);
    expect(isExpiringSoon(soon)).toBe(true);
    expect(isExpiringSoon(later)).toBe(false);
    expect(isExpiringSoon(null)).toBe(true);
    expect(isExpiringSoon(later, 0)).toBe(false);
  });

  it('should_restore_valid_session_from_localStorage', () => {
    useAuthStore.getState().setSession(session(), true);
    // 模拟应用重启：清内存态后从持久化恢复
    useAuthStore.setState({ session: null, remember: false });
    const restored = load();
    expect(restored.session?.accessToken).toBe('token-1');
    expect(restored.remember).toBe(true);
  });

  it('should_reject_restore_when_expiresAtMs_missing', () => {
    const { expiresAtMs: _omit, ...rest } = session();
    window.localStorage.setItem('acme.auth.session.v1', JSON.stringify(rest));
    window.localStorage.setItem('acme.auth.remember.v1', '1');
    const restored = load();
    expect(restored.session).toBeNull();
    expect(restored.remember).toBe(false);
  });

  it('should_reject_restore_when_field_type_mismatch', () => {
    const broken = { ...session(), userId: 'not-a-number' };
    window.localStorage.setItem('acme.auth.session.v1', JSON.stringify(broken));
    expect(load().session).toBeNull();
  });

  it('should_reject_restore_when_payload_not_object', () => {
    window.localStorage.setItem('acme.auth.session.v1', JSON.stringify('garbage'));
    expect(load().session).toBeNull();
  });

  it('should_reject_restore_when_json_malformed', () => {
    window.localStorage.setItem('acme.auth.session.v1', '{oops');
    expect(load().session).toBeNull();
  });
});
