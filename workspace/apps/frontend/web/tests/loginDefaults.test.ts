import { describe, expect, it } from 'vitest';
import { loginFormDefaults } from '../src/pages/loginDefaults';

/** 开发态登录预填口径：dev server 预填超管默认凭据，非开发态（含构建产物）恒为空。 */
describe('loginDefaults', () => {
  it('should_return_admin_defaults_when_is_dev', () => {
    expect(loginFormDefaults(true)).toEqual({ username: 'admin', password: 'admin123' });
  });

  it('should_return_null_when_not_dev', () => {
    expect(loginFormDefaults(false)).toBeNull();
  });
});
